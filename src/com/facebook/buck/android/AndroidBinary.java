/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.PACKAGING;

import com.android.common.SdkConstants;
import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.event.LogEvent;
import com.facebook.buck.java.Classpaths;
import com.facebook.buck.java.HasClasspathEntries;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.shell.AbstractGenruleStep;
import com.facebook.buck.shell.EchoStep;
import com.facebook.buck.shell.SymlinkFilesIntoDirectoryStep;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.zip.RepackZipEntriesStep;
import com.facebook.buck.zip.ZipDirectoryWithMaxDeflateStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.hash.HashCode;
import com.google.common.io.Files;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <pre>
 * android_binary(
 *   name = 'messenger',
 *   manifest = 'AndroidManifest.xml',
 *   target = 'Google Inc.:Google APIs:16',
 *   deps = [
 *     '//src/com/facebook/messenger:messenger_library',
 *   ],
 * )
 * </pre>
 */
public class AndroidBinary extends AbstractBuildable implements
    HasAndroidPlatformTarget, HasClasspathEntries, InstallableApk, AbiRule {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, PACKAGING);

  /**
   * This is the path from the root of the APK that should contain the metadata.txt and
   * secondary-N.dex.jar files for secondary dexes.
   */
  static final String SECONDARY_DEX_SUBDIR = "assets/secondary-program-dex-jars";

  /**
   * The largest file size Froyo will deflate.
   */
  private static final long FROYO_DEFLATE_LIMIT_BYTES = 1 << 20;

  /** Options to use with {@link DxStep} when merging pre-dexed files. */
  static final EnumSet<DxStep.Option> DX_MERGE_OPTIONS = EnumSet.of(
      DxStep.Option.USE_CUSTOM_DX_IF_AVAILABLE,
      DxStep.Option.NO_OPTIMIZE);
  private final Optional<Path> proguardJarOverride;
  private final Optional<Path> aaptOverride;

  /**
   * This list of package types is taken from the set of targets that the default build.xml provides
   * for Android projects.
   * <p>
   * Note: not all package types are supported. If unsupported, will be treated as "DEBUG".
   */
  static enum PackageType {
    DEBUG,
    INSTRUMENTED,
    RELEASE,
    TEST,
    ;

    /**
     * @return true if ProGuard should be used to obfuscate the output
     */
    private final boolean isBuildWithObfuscation() {
      return this == RELEASE;
    }

    final boolean isCrunchPngFiles() {
      return this == RELEASE;
    }
  }

  static enum TargetCpuType {
    ARM,
    ARMV7,
    X86,
    MIPS,
  }

  private final BuildRuleParams originalBuildRuleParams;
  private final JavacOptions javacOptions;
  private final SourcePath manifest;
  private final String target;
  private final ImmutableSortedSet<BuildRule> originalDeps;
  private final ImmutableSortedSet<BuildRule> classpathDeps;
  private final Keystore keystore;
  private final PackageType packageType;
  private DexSplitMode dexSplitMode;
  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  private final boolean useAndroidProguardConfigWithOptimizations;
  private final Optional<Integer> optimizationPasses;
  private final Optional<SourcePath> proguardConfig;
  private final ResourceCompressionMode resourceCompressionMode;
  private final ImmutableSet<TargetCpuType> cpuFilters;
  private final ResourceFilter resourceFilter;
  private final Path primaryDexPath;
  private final boolean buildStringSourceMap;
  private final boolean disablePreDex;
  private final boolean exopackage;
  private final ImmutableSortedSet<BuildRule> preprocessJavaClassesDeps;
  private final Optional<String> preprocessJavaClassesBash;

  // All the following fields are set in {@link #getEnhancedDeps(BuildRuleResolver)}.
  protected ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex;
  protected AndroidResourceDepsFinder androidResourceDepsFinder;
  private FilteredResourcesProvider filteredResourcesProvider;
  private UberRDotJava uberRDotJava;
  private AaptPackageResources aaptPackageResources;
  private Optional<PackageStringAssets> packageStringAssets;
  private Optional<PreDexMerge> preDexMerge;
  private Optional<ComputeExopackageDepsAbi> computeExopackageDepsAbi;

  /**
   * @param aaptOverride
   * @param target the Android platform version to target, e.g., "Google Inc.:Google APIs:16". You
   *     can find the list of valid values on your system by running
   *     {@code android list targets --compact}.
   */
  AndroidBinary(
      BuildRuleParams params,
      JavacOptions javacOptions,
      Optional<Path> proguardJarOverride,
      Optional<Path> aaptOverride,
      SourcePath manifest,
      String target,
      ImmutableSortedSet<BuildRule> originalDeps,
      Keystore keystore,
      PackageType packageType,
      DexSplitMode dexSplitMode,
      Set<BuildTarget> buildTargetsToExcludeFromDex,
      boolean useAndroidProguardConfigWithOptimizations,
      Optional<Integer> proguardOptimizationPasses,
      Optional<SourcePath> proguardConfig,
      ResourceCompressionMode resourceCompressionMode,
      Set<TargetCpuType> cpuFilters,
      ResourceFilter resourceFilter,
      boolean buildStringSourceMap,
      boolean disablePreDex,
      boolean exopackage,
      Set<BuildRule> preprocessJavaClassesDeps,
      Optional<String> preprocessJavaClassesBash) {
    this.originalBuildRuleParams = Preconditions.checkNotNull(params);
    this.javacOptions = Preconditions.checkNotNull(javacOptions);
    this.proguardJarOverride = Preconditions.checkNotNull(proguardJarOverride);
    this.aaptOverride = Preconditions.checkNotNull(aaptOverride);
    this.manifest = Preconditions.checkNotNull(manifest);
    this.target = Preconditions.checkNotNull(target);
    this.originalDeps = Preconditions.checkNotNull(originalDeps);
    this.classpathDeps = originalDeps;
    this.keystore = Preconditions.checkNotNull(keystore);
    this.packageType = Preconditions.checkNotNull(packageType);
    this.dexSplitMode = Preconditions.checkNotNull(dexSplitMode);
    this.buildTargetsToExcludeFromDex = ImmutableSet.copyOf(
        Preconditions.checkNotNull(buildTargetsToExcludeFromDex));
    this.useAndroidProguardConfigWithOptimizations = useAndroidProguardConfigWithOptimizations;
    this.optimizationPasses = Preconditions.checkNotNull(proguardOptimizationPasses);
    this.proguardConfig = Preconditions.checkNotNull(proguardConfig);
    this.resourceCompressionMode = Preconditions.checkNotNull(resourceCompressionMode);
    this.cpuFilters = ImmutableSet.copyOf(cpuFilters);
    this.resourceFilter = Preconditions.checkNotNull(resourceFilter);
    this.buildStringSourceMap = buildStringSourceMap;
    this.disablePreDex = disablePreDex;
    this.exopackage = exopackage;
    this.preprocessJavaClassesDeps = ImmutableSortedSet.copyOf(preprocessJavaClassesDeps);
    this.preprocessJavaClassesBash = Preconditions.checkNotNull(preprocessJavaClassesBash);
    this.primaryDexPath = getPrimaryDexPath(params.getBuildTarget());
  }

  @Override
  public ImmutableSortedSet<BuildRule> getEnhancedDeps(BuildRuleResolver resolver) {
    final ImmutableSortedSet<BuildRule> enhancedDeps =
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(originalDeps)
            .addAll(preprocessJavaClassesDeps)
            .add(resolver.get(keystore.getBuildTarget()))
            .build();

    AndroidTransitiveDependencyGraph androidTransitiveDependencyGraph =
        new AndroidTransitiveDependencyGraph(getClasspathDeps());
    // Create the BuildRule and Buildable for UberRDotJava.
    boolean allowNonExistentRule =
          false;
    ImmutableSortedSet<BuildRule> buildRulesToExcludeFromDex = BuildRules.toBuildRulesFor(
        getBuildTarget(),
        resolver,
        buildTargetsToExcludeFromDex,
        allowNonExistentRule);
    rulesToExcludeFromDex = FluentIterable.from(buildRulesToExcludeFromDex)
        .filter(
            new Predicate<BuildRule>() {
              @Override
              public boolean apply(BuildRule input) {
                return input.getBuildable() instanceof JavaLibrary;
              }
            })
        .transform(
            new Function<BuildRule, JavaLibrary>() {
              @Override
              public JavaLibrary apply(BuildRule input) {
                return (JavaLibrary) input.getBuildable();
              }
            })
        .toSortedSet(HasBuildTarget.BUILD_TARGET_COMPARATOR);
    androidResourceDepsFinder = new AndroidResourceDepsFinder(
        androidTransitiveDependencyGraph,
        rulesToExcludeFromDex) {
      @Override
      protected ImmutableList<HasAndroidResourceDeps> findMyAndroidResourceDeps() {
        return UberRDotJavaUtil.getAndroidResourceDeps(enhancedDeps);
      }
    };

    boolean shouldPreDex = !disablePreDex &&
        PackageType.DEBUG.equals(packageType) &&
        !preprocessJavaClassesBash.isPresent();

    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        originalBuildRuleParams.copyWithChangedDeps(enhancedDeps),
        resolver,
        resourceCompressionMode,
        resourceFilter,
        androidResourceDepsFinder,
        manifest,
        packageType,
        cpuFilters,
        buildStringSourceMap,
        shouldPreDex,
        primaryDexPath,
        dexSplitMode,
        buildTargetsToExcludeFromDex,
        javacOptions,
        exopackage,
        keystore,
        aaptOverride);
    AndroidBinaryGraphEnhancer.EnhancementResult result =
        graphEnhancer.createAdditionalBuildables();
    setGraphEnhancementResult(result);

    return result.getFinalDeps();
  }

  protected void setGraphEnhancementResult(AndroidBinaryGraphEnhancer.EnhancementResult result) {
    filteredResourcesProvider = result.getFilteredResourcesProvider();
    uberRDotJava = result.getUberRDotJava();
    aaptPackageResources = result.getAaptPackageResources();
    packageStringAssets = result.getPackageStringAssets();
    preDexMerge = result.getPreDexMerge();
    computeExopackageDepsAbi = result.getComputeExopackageDepsAbi();

    if (exopackage && !preDexMerge.isPresent()) {
      throw new IllegalArgumentException(getBuildTarget() +
          " specified exopackage without pre-dexing, which is invalid.");
    }

    if (exopackage) {
      Preconditions.checkArgument(computeExopackageDepsAbi.isPresent(),
          "computeExopackageDepsAbi must be set if exopackage is true.");
    }
  }

  public static Path getPrimaryDexPath(BuildTarget buildTarget) {
    return BuildTargets.getBinPath(buildTarget, ".dex/%s/classes.dex");
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return originalBuildRuleParams.getBuildTarget();
  }

  @Override
  public String getAndroidPlatformTarget() {
    return target;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    builder
        .setReflectively("target", target)
        .setReflectively("keystore", keystore.getBuildTarget())
        .setReflectively("classpathDeps", FluentIterable
                .from(classpathDeps)
                .transform(new Function<BuildRule, String>() {
                             @Override
                             public String apply(BuildRule buildRule) {
                               return buildRule.getFullyQualifiedName();
                             }
                           })
                .toList())
        .setReflectively("packageType", packageType)
        .setReflectively(
            "useAndroidProguardConfigWithOptimizations", useAndroidProguardConfigWithOptimizations)
        .setReflectively(
            "optimizationPasses", optimizationPasses)
        .setReflectively("resourceCompressionMode", resourceCompressionMode)
        .setReflectively("cpuFilters", ImmutableSortedSet.copyOf(cpuFilters))
        .setReflectively("exopackage", exopackage)
        .setReflectively("preprocessJavaClassesBash", preprocessJavaClassesBash)
        .setReflectively("preprocessJavaClassesDeps", preprocessJavaClassesDeps)
        .setReflectively("proguardJarOverride", proguardJarOverride);

    for (JavaLibrary buildable : rulesToExcludeFromDex) {
      buildable.appendDetailsToRuleKey(builder);
    }

    return dexSplitMode.appendToRuleKey("dexSplitMode", builder);
  }

  public ImmutableSortedSet<JavaLibrary> getRulesToExcludeFromDex() {
    return rulesToExcludeFromDex;
  }

  public Set<BuildTarget> getBuildTargetsToExcludeFromDex() {
    return buildTargetsToExcludeFromDex;
  }

  public Optional<SourcePath> getProguardConfig() {
    return proguardConfig;
  }

  public boolean isRelease() {
    return packageType == PackageType.RELEASE;
  }

  private boolean isCompressResources(){
    return resourceCompressionMode.isCompressResources();
  }

  public ResourceCompressionMode getResourceCompressionMode() {
    return resourceCompressionMode;
  }

  public ImmutableSet<TargetCpuType> getCpuFilters() {
    return this.cpuFilters;
  }

  public ResourceFilter getResourceFilter() {
    return resourceFilter;
  }
  @VisibleForTesting
  FilteredResourcesProvider getFilteredResourcesProvider() {
    return filteredResourcesProvider;
  }

  public ImmutableSortedSet<BuildRule> getPreprocessJavaClassesDeps() {
    return preprocessJavaClassesDeps;
  }

  public Optional<String> getPreprocessJavaClassesBash() {
    return preprocessJavaClassesBash;
  }

  public Optional<Integer> getOptimizationPasses() {
    return optimizationPasses;
  }

  /**
   * Native libraries compiled for different CPU architectures are placed in the
   * respective ABI subdirectories, such as 'armeabi', 'armeabi-v7a', 'x86' and 'mips'.
   * This looks at the cpu filter and returns the correct subdirectory. If cpu filter is
   * not present or not supported, returns Optional.absent();
   */
  private static Optional<String> getAbiDirectoryComponent(TargetCpuType cpuType) {
    String component = null;
    if (cpuType.equals(TargetCpuType.ARM)) {
      component = SdkConstants.ABI_ARMEABI;
    } else if (cpuType.equals(TargetCpuType.ARMV7)) {
      component = SdkConstants.ABI_ARMEABI_V7A;
    } else if (cpuType.equals(TargetCpuType.X86)) {
      component = SdkConstants.ABI_INTEL_ATOM;
    } else if (cpuType.equals(TargetCpuType.MIPS)) {
      component = SdkConstants.ABI_MIPS;
    }
    return Optional.fromNullable(component);

  }

  @VisibleForTesting
  static void copyNativeLibrary(Path sourceDir,
      final Path destinationDir,
      ImmutableSet<TargetCpuType> cpuFilters,
      ImmutableList.Builder<Step> steps) {

    if (cpuFilters.isEmpty()) {
      steps.add(
          CopyStep.forDirectory(
              sourceDir,
              destinationDir,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    } else {
      for (TargetCpuType cpuType : cpuFilters) {
        Optional<String> abiDirectoryComponent = getAbiDirectoryComponent(cpuType);
        Preconditions.checkState(abiDirectoryComponent.isPresent());

        final Path libSourceDir = sourceDir.resolve(abiDirectoryComponent.get());
        Path libDestinationDir = destinationDir.resolve(abiDirectoryComponent.get());

        final MkdirStep mkDirStep = new MkdirStep(libDestinationDir);
        final CopyStep copyStep = CopyStep.forDirectory(
            libSourceDir,
            libDestinationDir,
            CopyStep.DirectoryMode.CONTENTS_ONLY);
        steps.add(new Step() {
          @Override
          public int execute(ExecutionContext context) {
            if (!context.getProjectFilesystem().exists(libSourceDir)) {
              return 0;
            }
            if (mkDirStep.execute(context) == 0 && copyStep.execute(context) == 0) {
              return 0;
            }
            return 1;
          }

          @Override
          public String getShortName() {
            return "copy_native_libraries";
          }

          @Override
          public String getDescription(ExecutionContext context) {
            ImmutableList.Builder<String> stringBuilder = ImmutableList.builder();
            stringBuilder.add(String.format("[ -d %s ]", libSourceDir.toString()));
            stringBuilder.add(mkDirStep.getDescription(context));
            stringBuilder.add(copyStep.getDescription(context));
            return Joiner.on(" && ").join(stringBuilder.build());
          }
        });
      }
    }

    // Rename native files named like "*-disguised-exe" to "lib*.so" so they will be unpacked
    // by the Android package installer.  Then they can be executed like normal binaries
    // on the device.
    steps.add(
        new AbstractExecutionStep("rename_native_executables") {

          @Override
          public int execute(ExecutionContext context) {

            ProjectFilesystem filesystem = context.getProjectFilesystem();
            final ImmutableSet.Builder<Path> executablesBuilder = ImmutableSet.builder();
            try {
              filesystem.walkRelativeFileTree(destinationDir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                      if (file.toString().endsWith("-disguised-exe")) {
                        executablesBuilder.add(file);
                      }
                      return FileVisitResult.CONTINUE;
                    }
                  });
              for (Path exePath : executablesBuilder.build()) {
                Path fakeSoPath = Paths.get(
                    exePath.toString().replaceAll("/([^/]+)-disguised-exe$", "/lib$1.so"));
                filesystem.move(exePath, fakeSoPath);
              }
            } catch (IOException e) {
              context.logError(e, "Renaming native executables failed.");
              return 1;
            }
            return 0;
          }
        });
  }

  /** The APK at this path is the final one that points to an APK that a user should install. */
  @Override
  public Path getApkPath() {
    return Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".apk"));
  }

  @Override
  public Path getPathToOutputFile() {
    return getApkPath();
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    ImmutableList.Builder<SourcePath> sourcePaths = ImmutableList.builder();
    sourcePaths.add(manifest);

    Optionals.addIfPresent(proguardConfig, sourcePaths);
    sourcePaths.addAll(dexSplitMode.getSourcePaths());

    return SourcePaths.filterInputsToCompareToOutput(sourcePaths.build());
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    final AndroidTransitiveDependencies transitiveDependencies = findTransitiveDependencies();

    // Create the .dex files if we aren't doing pre-dexing.
    AndroidDexTransitiveDependencies dexTransitiveDependencies =
        findDexTransitiveDependencies();
    Path signedApkPath = getSignedApkPath();
    DexFilesInfo dexFilesInfo = addFinalDxSteps(
        context,
        transitiveDependencies,
        dexTransitiveDependencies,
        filteredResourcesProvider.getResDirectories(),
        buildableContext,
        steps);

    ////
    // BE VERY CAREFUL adding any code below here.
    // Any inputs to apkbuilder must be reflected in the hash returned by getAbiKeyForDeps.
    ////

    // Copy the transitive closure of files in native_libs to a single directory, if any.
    ImmutableSet<Path> nativeLibraryDirectories;
    if (!transitiveDependencies.nativeLibsDirectories.isEmpty()) {
      Path pathForNativeLibs = getPathForNativeLibs();
      Path libSubdirectory = pathForNativeLibs.resolve("lib");
      steps.add(new MakeCleanDirectoryStep(libSubdirectory));
      for (Path nativeLibDir : transitiveDependencies.nativeLibsDirectories) {
        copyNativeLibrary(nativeLibDir, libSubdirectory, cpuFilters, steps);
      }
      nativeLibraryDirectories = ImmutableSet.of(libSubdirectory);
    } else {
      nativeLibraryDirectories = ImmutableSet.of();
    }

    // If non-english strings are to be stored as assets, pass them to ApkBuilder.
    ImmutableSet.Builder<Path> zipFiles = ImmutableSet.builder();
    zipFiles.addAll(dexFilesInfo.secondaryDexZips);
    if (packageStringAssets.isPresent()) {
      final Path pathToStringAssetsZip = packageStringAssets.get().getPathToStringAssetsZip();
      zipFiles.add(pathToStringAssetsZip);
      // TODO(natthu): Remove this check once we figure out what's exactly causing APKs missing
      // string assets zip sometimes.
      steps.add(
          new AbstractExecutionStep("check_string_assets_zip_exists") {
            @Override
            public int execute(ExecutionContext context) {
              if (!context.getProjectFilesystem().exists(pathToStringAssetsZip)) {
                context.postEvent(LogEvent.severe(
                        "Zip file containing non-english strings was not created: %s",
                        pathToStringAssetsZip));
                return 1;
              }
              return 0;
            }
          });
    }

    ApkBuilderStep apkBuilderCommand = new ApkBuilderStep(
        aaptPackageResources.getResourceApkPath(),
        getSignedApkPath(),
        dexFilesInfo.primaryDexPath,
        /* javaResourcesDirectories */ ImmutableSet.<String>of(),
        nativeLibraryDirectories,
        zipFiles.build(),
        dexTransitiveDependencies.pathsToThirdPartyJars,
        keystore.getPathToStore(),
        keystore.getPathToPropertiesFile(),
        /* debugMode */ false);
    steps.add(apkBuilderCommand);


    Path apkToAlign;
    // Optionally, compress the resources file in the .apk.
    if (this.isCompressResources()) {
      Path compressedApkPath = getCompressedResourcesApkPath();
      apkToAlign = compressedApkPath;
      RepackZipEntriesStep arscComp = new RepackZipEntriesStep(
          signedApkPath,
          compressedApkPath,
          ImmutableSet.of("resources.arsc"));
      steps.add(arscComp);
    } else {
      apkToAlign = signedApkPath;
    }

    Path apkPath = getApkPath();
    ZipalignStep zipalign = new ZipalignStep(apkToAlign, apkPath);
    steps.add(zipalign);

    // Inform the user where the APK can be found.
    EchoStep success = new EchoStep(
        String.format("built APK for %s at %s",
            getBuildTarget().getFullyQualifiedName(),
            apkPath));
    steps.add(success);

    buildableContext.recordArtifact(getApkPath());
    return steps.build();
  }

  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    // For non-exopackages, there is no benefit to the ABI optimization, so we want to disable it.
    // Returning our RuleKey has this effect because we will never get an ABI match after a
    // RuleKey miss.
    if (!exopackage) {
      // TODO(natthu): This is a hack which avoids having to return rule key from the buildable.
      // Once we figure out a way to expose the build engine to a buildable, this should return the
      // rule key as before.
      return Sha1HashCode.newRandomHashCode();
    }

    return computeExopackageDepsAbi.get().getAndroidBinaryAbiHash();
  }

  /**
   * Adds steps to do the final dexing or dex merging before building the apk.
   */
  private DexFilesInfo addFinalDxSteps(
      BuildContext context,
      final AndroidTransitiveDependencies transitiveDependencies,
      final AndroidDexTransitiveDependencies dexTransitiveDependencies,
      ImmutableSet<Path> resDirectories,
      BuildableContext buildableContext,
      ImmutableList.Builder<Step> steps) {
    // Execute preprocess_java_classes_binary, if appropriate.
    ImmutableSet<Path> classpathEntriesToDex;
    if (preprocessJavaClassesBash.isPresent()) {
      // Symlink everything in dexTransitiveDependencies.classpathEntriesToDex to the input
      // directory. Expect parallel outputs in the output directory and update classpathEntriesToDex
      // to reflect that.
      final Path preprocessJavaClassesInDir = getBinPath("java_classes_preprocess_in_%s");
      final Path preprocessJavaClassesOutDir = getBinPath("java_classes_preprocess_out_%s");
      steps.add(new MakeCleanDirectoryStep(preprocessJavaClassesInDir));
      steps.add(new MakeCleanDirectoryStep(preprocessJavaClassesOutDir));
      steps.add(new SymlinkFilesIntoDirectoryStep(
          context.getProjectRoot(),
          dexTransitiveDependencies.classpathEntriesToDex,
          preprocessJavaClassesInDir));
      classpathEntriesToDex = FluentIterable.from(dexTransitiveDependencies.classpathEntriesToDex)
          .transform(new Function<Path, Path>() {
            @Override
            public Path apply(Path classpathEntry) {
              return preprocessJavaClassesOutDir.resolve(classpathEntry);
            }
          })
          .toSet();

      AbstractGenruleStep.CommandString commandString = new AbstractGenruleStep.CommandString(
          /* cmd */ Optional.<String>absent(),
          /* bash */ preprocessJavaClassesBash,
          /* cmdExe */ Optional.<String>absent());
      steps.add(new AbstractGenruleStep(
          AndroidBinaryDescription.TYPE,
          this.getBuildTarget(),
          commandString,
          preprocessJavaClassesDeps,
          preprocessJavaClassesInDir.toFile()) {

        @Override
        protected void addEnvironmentVariables(
            ExecutionContext context,
            ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
          Function<Path, Path> aboslutifier = context.getProjectFilesystem().getAbsolutifier();
          environmentVariablesBuilder.put(
              "IN_JARS_DIR", aboslutifier.apply(preprocessJavaClassesInDir).toString());
          environmentVariablesBuilder.put(
              "OUT_JARS_DIR", aboslutifier.apply(preprocessJavaClassesOutDir).toString());

          Optional<AndroidPlatformTarget> platformTarget =
              context.getAndroidPlatformTargetOptional();

          if (!platformTarget.isPresent()) {
            return;
          }

          String bootclasspath = Joiner.on(':').join(
              Iterables.transform(
                  platformTarget.get().getBootclasspathEntries(),
                  aboslutifier));

          environmentVariablesBuilder.put("ANDROID_BOOTCLASSPATH", bootclasspath);
        }
      });

    } else {
      classpathEntriesToDex = dexTransitiveDependencies.classpathEntriesToDex;
    }

    // Execute proguard if desired (transforms input classpaths).
    if (packageType.isBuildWithObfuscation()) {
      classpathEntriesToDex = addProguardCommands(
          classpathEntriesToDex,
          transitiveDependencies.proguardConfigs,
          steps,
          resDirectories,
          buildableContext);
    }

    // Create the final DEX (or set of DEX files in the case of split dex).
    // The APK building command needs to take a directory of raw files, so primaryDexPath
    // can only contain .dex files from this build rule.

    // Create dex artifacts. If split-dex is used, the assets/ directory should contain entries
    // that look something like the following:
    //
    // assets/secondary-program-dex-jars/metadata.txt
    // assets/secondary-program-dex-jars/secondary-1.dex.jar
    // assets/secondary-program-dex-jars/secondary-2.dex.jar
    // assets/secondary-program-dex-jars/secondary-3.dex.jar
    //
    // The contents of the metadata.txt file should look like:
    // secondary-1.dex.jar fffe66877038db3af2cbd0fe2d9231ed5912e317 secondary.dex01.Canary
    // secondary-2.dex.jar b218a3ea56c530fed6501d9f9ed918d1210cc658 secondary.dex02.Canary
    // secondary-3.dex.jar 40f11878a8f7a278a3f12401c643da0d4a135e1a secondary.dex03.Canary
    //
    // The scratch directories that contain the metadata.txt and secondary-N.dex.jar files must be
    // listed in secondaryDexDirectoriesBuilder so that their contents will be compressed
    // appropriately for Froyo.
    ImmutableSet.Builder<Path> secondaryDexDirectoriesBuilder = ImmutableSet.builder();
    if (!preDexMerge.isPresent()) {
      steps.add(new MkdirStep(primaryDexPath.getParent()));

      addDexingSteps(
          classpathEntriesToDex,
          dexTransitiveDependencies.classNamesToHashesSupplier,
          secondaryDexDirectoriesBuilder,
          steps,
          primaryDexPath);
    } else if (!exopackage) {
      secondaryDexDirectoriesBuilder.addAll(preDexMerge.get().getSecondaryDexDirectories());
    }
    ImmutableSet<Path> secondaryDexDirectories = secondaryDexDirectoriesBuilder.build();

    // Due to limitations of Froyo, we need to ensure that all secondary zip files are STORED in
    // the final APK, not DEFLATED.  The only way to ensure this with ApkBuilder is to zip up the
    // the files properly and then add the zip files to the apk.
    ImmutableSet.Builder<Path> secondaryDexZips = ImmutableSet.builder();
    for (Path secondaryDexDirectory : secondaryDexDirectories) {
      // String the trailing slash from the directory name and add the zip extension.
      Path zipFile = Paths.get(secondaryDexDirectory.toString().replaceAll("/$", "") + ".zip");

      secondaryDexZips.add(zipFile);
      steps.add(new ZipDirectoryWithMaxDeflateStep(secondaryDexDirectory,
          zipFile,
          FROYO_DEFLATE_LIMIT_BYTES));
    }

    return new DexFilesInfo(primaryDexPath, secondaryDexZips.build());
  }

  public AndroidTransitiveDependencies findTransitiveDependencies() {
    return androidResourceDepsFinder.getAndroidTransitiveDependencies();
  }

  public AndroidDexTransitiveDependencies findDexTransitiveDependencies() {
    return androidResourceDepsFinder.getAndroidDexTransitiveDependencies(uberRDotJava);
  }

  /**
   * This is the path to the directory for generated files related to ProGuard. Ultimately, it
   * should include:
   * <ul>
   *   <li>proguard.txt
   *   <li>dump.txt
   *   <li>seeds.txt
   *   <li>usage.txt
   *   <li>mapping.txt
   *   <li>obfuscated.jar
   * </ul>
   * @return path to directory (will not include trailing slash)
   */
  @VisibleForTesting
  Path getPathForProGuardDirectory() {
    return BuildTargets.getGenPath(getBuildTarget(), ".proguard/%s");
  }

  /**
   * All native libs are copied to this directory before running aapt.
   */
  private Path getPathForNativeLibs() {
    return getBinPath("__native_libs_%s__");
  }

  public Keystore getKeystore() {
    return keystore;
  }

  public String getUnsignedApkPath() {
    return BuildTargets.getGenPath(getBuildTarget(), "%s.unsigned.apk").toString();
  }

  /** The APK at this path will be signed, but not zipaligned. */
  private Path getSignedApkPath() {
    return Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".signed.apk"));
  }

  /** The APK at this path will have compressed resources, but will not be zipaligned. */
  private Path getCompressedResourcesApkPath() {
    return Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".compressed.apk"));
  }

  private Path getBinPath(String format) {
    return BuildTargets.getBinPath(getBuildTarget(), format);
  }

  @VisibleForTesting
  Path getProguardOutputFromInputClasspath(Path classpathEntry) {
    // Hehe, this is so ridiculously fragile.
    Preconditions.checkArgument(!classpathEntry.isAbsolute(),
        "Classpath entries should be relative rather than absolute paths: %s",
        classpathEntry);
    String obfuscatedName =
        Files.getNameWithoutExtension(classpathEntry.toString()) + "-obfuscated.jar";
    Path dirName = classpathEntry.getParent();
    Path outputJar = getPathForProGuardDirectory().resolve(dirName).resolve(obfuscatedName);
    return outputJar;
  }

  /**
   * @return the resulting set of ProGuarded classpath entries to dex.
   */
  @VisibleForTesting
  ImmutableSet<Path> addProguardCommands(
      Set<Path> classpathEntriesToDex,
      Set<Path> depsProguardConfigs,
      ImmutableList.Builder<Step> steps,
      Set<Path> resDirectories,
      BuildableContext buildableContext) {
    final ImmutableSetMultimap<JavaLibrary, Path> classpathEntriesMap =
        getTransitiveClasspathEntries();
    ImmutableSet.Builder<Path> additionalLibraryJarsForProguardBuilder = ImmutableSet.builder();

    for (JavaLibrary buildRule : rulesToExcludeFromDex) {
      additionalLibraryJarsForProguardBuilder.addAll(classpathEntriesMap.get(buildRule));
    }

    // Clean out the directory for generated ProGuard files.
    Path proguardDirectory = getPathForProGuardDirectory();
    steps.add(new MakeCleanDirectoryStep(proguardDirectory));

    // Generate a file of ProGuard config options using aapt.
    Path generatedProGuardConfig = proguardDirectory.resolve("proguard.txt");
    GenProGuardConfigStep genProGuardConfig = new GenProGuardConfigStep(
        aaptPackageResources.getAndroidManifestXml(),
        resDirectories,
        generatedProGuardConfig,
        aaptOverride);
    steps.add(genProGuardConfig);

    // Create list of proguard Configs for the app project and its dependencies
    ImmutableSet.Builder<Path> proguardConfigsBuilder = ImmutableSet.builder();
    proguardConfigsBuilder.addAll(depsProguardConfigs);
    if (proguardConfig.isPresent()) {
      proguardConfigsBuilder.add(proguardConfig.get().resolve());
    }

    // Transform our input classpath to a set of output locations for each input classpath.
    // TODO(devjasta): the output path we choose is the result of a slicing function against
    // input classpath. This is fragile and should be replaced with knowledge of the BuildTarget.
    final ImmutableMap<Path, Path> inputOutputEntries = FluentIterable
        .from(classpathEntriesToDex)
        .toMap(new Function<Path, Path>() {
          @Override
          public Path apply(Path classpathEntry) {
            return getProguardOutputFromInputClasspath(classpathEntry);
          }
        });

    // Run ProGuard on the classpath entries.
    // TODO(user): ProGuardObfuscateStep's final argument should be a Path
    Step obfuscateCommand = ProGuardObfuscateStep.create(
        proguardJarOverride,
        generatedProGuardConfig,
        proguardConfigsBuilder.build(),
        useAndroidProguardConfigWithOptimizations,
        optimizationPasses,
        inputOutputEntries,
        additionalLibraryJarsForProguardBuilder.build(),
        proguardDirectory,
        buildableContext);
    steps.add(obfuscateCommand);

    // Apply the transformed inputs to the classpath (this will modify deps.classpathEntriesToDex
    // so that we're now dexing the proguarded artifacts).
    return ImmutableSet.copyOf(inputOutputEntries.values());
  }

  /**
   * Create dex artifacts for all of the individual directories of compiled .class files (or
   * the obfuscated jar files if proguard is used).  If split dex is used, multiple dex artifacts
   * will be produced.
   *  @param classpathEntriesToDex Full set of classpath entries that must make
   *     their way into the final APK structure (but not necessarily into the
   *     primary dex).
   * @param classNamesToHashesSupplier
   * @param secondaryDexDirectories The contract for updating this builder must match that
   *     of {@link PreDexMerge#getSecondaryDexDirectories()}.
   * @param steps List of steps to add to.
   * @param primaryDexPath Output path for the primary dex file.
   */
  @VisibleForTesting
  void addDexingSteps(
      Set<Path> classpathEntriesToDex,
      Supplier<Map<String, HashCode>> classNamesToHashesSupplier,
      ImmutableSet.Builder<Path> secondaryDexDirectories,
      ImmutableList.Builder<Step> steps,
      Path primaryDexPath) {
    final Supplier<Set<Path>> primaryInputsToDex;
    final Optional<Path> secondaryDexDir;
    final Optional<Supplier<Multimap<Path, Path>>> secondaryOutputToInputs;

    if (shouldSplitDex()) {
      Optional<Path> proguardFullConfigFile = Optional.absent();
      Optional<Path> proguardMappingFile = Optional.absent();
      if (packageType.isBuildWithObfuscation()) {
        proguardFullConfigFile =
            Optional.of(getPathForProGuardDirectory().resolve("configuration.txt"));
        proguardMappingFile = Optional.of(getPathForProGuardDirectory().resolve("mapping.txt"));
      }

      // DexLibLoader expects that metadata.txt and secondary jar files are under this dir
      // in assets.

      // Intermediate directory holding the primary split-zip jar.
      Path splitZipDir = getBinPath("__%s_split_zip__");
      steps.add(new MakeCleanDirectoryStep(splitZipDir));
      Path primaryJarPath = splitZipDir.resolve("primary.jar");

      Path secondaryJarMetaDirParent = splitZipDir.resolve("secondary_meta");
      Path secondaryJarMetaDir = secondaryJarMetaDirParent.resolve(SECONDARY_DEX_SUBDIR);
      steps.add(new MakeCleanDirectoryStep(secondaryJarMetaDir));
      Path secondaryJarMeta = secondaryJarMetaDir.resolve("metadata.txt");

      // Intermediate directory holding _ONLY_ the secondary split-zip jar files.  This is
      // important because SmartDexingCommand will try to dx every entry in this directory.  It
      // does this because it's impossible to know what outputs split-zip will generate until it
      // runs.
      final Path secondaryZipDir = getBinPath("__%s_secondary_zip__");
      steps.add(new MakeCleanDirectoryStep(secondaryZipDir));

      // Run the split-zip command which is responsible for dividing the large set of input
      // classpaths into a more compact set of jar files such that no one jar file when dexed will
      // yield a dex artifact too large for dexopt or the dx method limit to handle.
      Path zipSplitReportDir = getBinPath("__%s_split_zip_report__");
      steps.add(new MakeCleanDirectoryStep(zipSplitReportDir));
      SplitZipStep splitZipCommand = new SplitZipStep(
          classpathEntriesToDex,
          secondaryJarMeta,
          primaryJarPath,
          secondaryZipDir,
          "secondary-%d.jar",
          proguardFullConfigFile,
          proguardMappingFile,
          dexSplitMode,
          zipSplitReportDir);
      steps.add(splitZipCommand);

      // Add the secondary dex directory that has yet to be created, but will be by the
      // smart dexing command.  Smart dex will handle "cleaning" this directory properly.
      Path secondaryDexParentDir = getBinPath("__%s_secondary_dex__/");
      secondaryDexDir = Optional.of(secondaryDexParentDir.resolve(SECONDARY_DEX_SUBDIR));
      steps.add(new MkdirStep(secondaryDexDir.get()));

      secondaryDexDirectories.add(secondaryJarMetaDirParent);
      secondaryDexDirectories.add(secondaryDexParentDir);

      // Adjust smart-dex inputs for the split-zip case.
      primaryInputsToDex = Suppliers.<Set<Path>>ofInstance(ImmutableSet.of(primaryJarPath));
      Supplier<Multimap<Path, Path>> secondaryOutputToInputsMap =
          splitZipCommand.getOutputToInputsMapSupplier(secondaryDexDir.get());
      secondaryOutputToInputs = Optional.of(secondaryOutputToInputsMap);
    } else {
      // Simple case where our inputs are the natural classpath directories and we don't have
      // to worry about secondary jar/dex files.
      primaryInputsToDex = Suppliers.ofInstance(classpathEntriesToDex);
      secondaryDexDir = Optional.absent();
      secondaryOutputToInputs = Optional.absent();
    }

    HashInputJarsToDexStep hashInputJarsToDexStep = new HashInputJarsToDexStep(
        primaryInputsToDex,
        secondaryOutputToInputs,
        classNamesToHashesSupplier);
    steps.add(hashInputJarsToDexStep);

    // Stores checksum information from each invocation to intelligently decide when dx needs
    // to be re-run.
    Path successDir = getBinPath("__%s_smart_dex__/.success");
    steps.add(new MkdirStep(successDir));

    // Add the smart dexing tool that is capable of avoiding the external dx invocation(s) if
    // it can be shown that the inputs have not changed.  It also parallelizes dx invocations
    // where applicable.
    //
    // Note that by not specifying the number of threads this command will use it will select an
    // optimal default regardless of the value of --num-threads.  This decision was made with the
    // assumption that --num-threads specifies the threading of build rule execution and does not
    // directly apply to the internal threading/parallelization details of various build commands
    // being executed.  For example, aapt is internally threaded by default when preprocessing
    // images.
    EnumSet<DxStep.Option> dxOptions = PackageType.RELEASE.equals(packageType)
        ? EnumSet.noneOf(DxStep.Option.class)
        : EnumSet.of(DxStep.Option.NO_OPTIMIZE);
    SmartDexingStep smartDexingCommand = new SmartDexingStep(
        primaryDexPath,
        primaryInputsToDex,
        secondaryDexDir,
        secondaryOutputToInputs,
        hashInputJarsToDexStep,
        successDir,
        Optional.<Integer>absent(),
        dxOptions);
    steps.add(smartDexingCommand);
  }

  @Override
  public Path getManifestPath() {
    return aaptPackageResources.getAndroidManifestXml();
  }

  String getTarget() {
    return target;
  }

  boolean shouldSplitDex() {
    return dexSplitMode.isShouldSplitDex();
  }

  @Override
  public Optional<ExopackageInfo> getExopackageInfo() {
    if (!exopackage) {
      return Optional.absent();
    }
    return Optional.of(
        new ExopackageInfo(
            preDexMerge.get().getMetadataTxtPath(),
            preDexMerge.get().getDexDirectory()));
  }

  boolean isUseAndroidProguardConfigWithOptimizations() {
    return useAndroidProguardConfigWithOptimizations;
  }

  public ImmutableSortedSet<BuildRule> getClasspathDeps() {
    return classpathDeps;
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
    // This is used primarily for buck audit classpath.
    return Classpaths.getClasspathEntries(getClasspathDeps());
  }

  /**
   * Encapsulates the information about dexing output that must be passed to ApkBuilder.
   */
  private static class DexFilesInfo {
    final Path primaryDexPath;
    final ImmutableSet<Path> secondaryDexZips;

    DexFilesInfo(Path primaryDexPath, ImmutableSet<Path> secondaryDexZips) {
      this.primaryDexPath = Preconditions.checkNotNull(primaryDexPath);
      this.secondaryDexZips = Preconditions.checkNotNull(secondaryDexZips);
    }
  }
}
