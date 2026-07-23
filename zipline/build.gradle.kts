import java.util.concurrent.TimeUnit

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.plugin.NATIVE_COMPILER_PLUGIN_CLASSPATH_CONFIGURATION_NAME
import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithTests
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.gradle.api.publish.maven.MavenPublication

plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
  id("com.android.library")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("co.touchlab.cklib")
  id("com.github.gmazzo.buildconfig")
  id("binary-compatibility-validator")
  id("com.jakewharton.test-distribution")
}

val copyTestingJs = tasks.register<Copy>("copyTestingJs") {
  dependsOn(":zipline-testing:compileDevelopmentLibraryKotlinJs")
  destinationDir = rootProject.layout.buildDirectory.dir("generated/testingJs").get().asFile
  from(rootDir.resolve("zipline-testing/build/compileSync/js/main/developmentLibrary/kotlin"))
}

// Hermes is built in two ways:
//   1. For Android and host JVM: `buildHermesJni` runs `host-build.sh`.
//   2. For iOS: the per-target Gradle tasks below run CMake and produce a
//      single static archive `libhermesvm.a`. The archive is embedded in the
//      iOS Kotlin/Native klib via cinterop staticLibraries, so consumers only
//      need the Gradle dependency; no separate framework/dylib is required.
tasks.withType<KotlinNativeTest>().configureEach {
  dependsOn(":zipline-testing:compileDevelopmentLibraryKotlinJs")
}

dependencies {
  add(PLUGIN_CLASSPATH_CONFIGURATION_NAME, projects.ziplineKotlinPlugin)
  add(NATIVE_COMPILER_PLUGIN_CLASSPATH_CONFIGURATION_NAME, projects.ziplineKotlinPlugin)
}

kotlin {
  androidTarget {
    // Substitute release AAR with debug AAR when the
    // zipline-debug project property is set (e.g.,
    // ./gradlew :zipline:publishToMavenLocal -Pzipline-debug=true).
    // Use this to produce a debug AAR (with full symbols) that can be
    // consumed by downstream apps for debugging runtime crashes.
    val debugAar = providers.gradleProperty("zipline-debug").orNull?.toBooleanStrictOrNull() == true
    if (debugAar) {
      publishLibraryVariants("debug")
    } else {
      publishLibraryVariants("release")
    }
  }
  jvm()

  js {
    nodejs()
  }

  linuxX64()
  macosX64()
  macosArm64()
  iosArm64()
  iosX64()
  iosSimulatorArm64()
  tvosArm64()
  tvosSimulatorArm64()
  tvosX64()

  applyDefaultHierarchyTemplate()

  sourceSets {
    val commonMain by getting {
      dependencies {
        api(libs.kotlinx.coroutines.core)
        api(libs.kotlinx.serialization.core)
        implementation(libs.kotlinx.serialization.json)
      }
    }
    val commonTest by getting {
      dependencies {
        implementation(libs.assertk)
        implementation(kotlin("test"))
        implementation(projects.ziplineCryptography)
        implementation(libs.kotlinx.coroutines.test)
        implementation(projects.ziplineTesting)
      }
    }

    val hostMain by creating {
      dependsOn(commonMain)
      dependencies {
        api(libs.okio.core)
      }
    }
    val hostTest by creating {
      dependsOn(commonTest)
    }

    val jniMain by creating {
      dependsOn(hostMain)
      dependencies {
        api(libs.androidx.annotation)
      }
    }
    val jniTest by creating {
      dependsOn(hostTest)
    }

    val androidMain by getting {
      dependsOn(jniMain)
    }
    val androidInstrumentedTest by getting {
      dependsOn(jniTest)
      dependencies {
        implementation(libs.assertk)
        implementation(libs.junit)
        implementation(libs.androidx.test.runner)
        implementation(libs.kotlinx.coroutines.test)
        implementation(kotlin("test"))
        implementation(projects.ziplineTesting)
      }
    }
    val jvmMain by getting {
      dependsOn(jniMain)
    }
    val jvmTest by getting {
      dependsOn(jniTest)
      resources.srcDir(copyTestingJs)
      dependencies {
        implementation(libs.junit)
        implementation(projects.ziplineTesting)
      }
    }

    val nativeMain by getting {
      dependsOn(hostMain)
    }
    val nativeTest by getting {
      dependsOn(hostTest)
    }

    targets.withType<KotlinNativeTarget> {
      val main by compilations.getting

      // iOS uses a generated cinterop definition that embeds the static
      // Hermes+glue archive built by the per-target Gradle tasks below.
      // macOS/Linux keep using the checked-in def file plus dynamic lookup
      // of the host dylib built by host-build.sh.
      val hermesDefFile = when (konanTarget.family) {
        Family.IOS -> {
          val defDir = layout.buildDirectory.dir("generated/cinterop").get().asFile
            .also { it.mkdirs() }
          val defFile = File(defDir, "hermes-${konanTarget.name}.def")
          val hermesStaticDir = layout.buildDirectory
            .dir("hermes-static/${konanTarget.name}/cmake").get().asFile
          defFile.writeText(
            """
            package = app.cash.zipline.hermes
            headers = ${file("native/hermes-ios/hermes-ios.h").absolutePath} ${file("native/hermes-core.h").absolutePath}
            compilerOpts = -I${file("native/hermes-ios").absolutePath} -I${file("native").absolutePath}
            staticLibraries = libhermesvm.a
            libraryPaths = ${hermesStaticDir.absolutePath}
            linkerOpts.ios = -framework Foundation -lsqlite3
            """.trimIndent()
          )
          defFile
        }
        else -> file("src/nativeInterop/cinterop/hermes.def")
      }

      main.cinterops {
        create("hermes") {
          defFile(hermesDefFile)
          packageName("app.cash.zipline.hermes")
          if (konanTarget.family != Family.IOS) {
            // Header/include paths must come from the DSL (not the def file) so
            // they are anchored at the project directory and stay portable.
            headers(file("native/hermes-ios/hermes-ios.h"))
            includeDirs(file("native/hermes-ios"), file("native"))
          }
        }
      }

      // The hermes.def cinterop file cannot use an absolute `-L` path (it must
      // stay portable across machines), so the library search path is supplied
      // here for every native binary (test executables need it too). iOS has
      // the static archive embedded in its klib, so it does not need -L.
      binaries.all {
        if (konanTarget.family != Family.IOS) {
          linkerOpts += "-L${rootDir}/zipline/build/hermes-ios"
        }
      }

      binaries.withType<Framework> {
        when (konanTarget.family) {
          Family.IOS -> linkerOpts += listOf(
            "-framework", "Foundation",
            "-lsqlite3",
          )
          else -> linkerOpts += listOf(
            "-lhermesvm",
            "-lsqlite3",
            // Tell the dynamic loader where to find bundled dylibs at runtime.
            // Consumers must place libhermesvm.dylib inside the framework's
            // Frameworks/ subdirectory (or the app's Frameworks/ directory) for
            // the loader to find it at app launch.
            "-rpath", "@loader_path/Frameworks",
            "-rpath", "@executable_path/Frameworks",
          )
        }
      }
    }

    targets.withType<KotlinNativeTargetWithTests<*>> {
      binaries {
        // Configure a separate test where code is compiled in release mode.
        test(setOf(NativeBuildType.RELEASE))
      }
      testRuns {
        create("release") {
          setExecutionSourceFrom(binaries.getByName("releaseTest") as TestExecutable)
        }
      }
    }
  }
}

buildConfig {
  useKotlinOutput {
    internalVisibility = true
    topLevelConstants = true
  }

  sourceSets.named("hostMain") {
    packageName("app.cash.zipline")
    buildConfigField("String", "jsEngineVersion", "\"${jsEngineVersion()}\"")
    buildConfigField("String", "hermesLibraryName", "\"${hermesLibraryName()}\"")
  }
}

// JsEngine is built externally (see zipline/host-build.sh for the host libs and
// src/androidMain/CMakeLists.txt for the Android libs). The cklib block that
// compiled QuickJS into a Kotlin/Native bitcode library is gone — Kotlin/Native
// targets currently stub the engine (see nativeMain/JsEngine.kt).

fun jsEngineVersion(): String {
  // The vendored JsEngine source is pinned by its git revision (see
  // zipline/native/hermes/hermes-git-revision). Expose that here so the
  // generated BuildConfig mirrors the same value across host + Android.
  return File(projectDir, "native/hermes/hermes-git-revision").readText().trim()
}

fun hermesLibraryName(): String {
  // Lean mode is always enabled for Maven publish builds. This excludes
  // JIT/parser for smaller APKs; compile() will throw UnsupportedOperationException.
  return "hermesvmlean"
}

// -----------------------------------------------------------------------------
// JsEngine host-side toolchain. The Android build needs a host
// `hermesc` (and `shermes`) binary to AOT-compile the InternalJavaScript
// stub. We pre-build them once per configure via a Gradle task, then
// point the Android CMake at the resulting ImportHostCompilers.cmake.
// This mirrors the React Native JsEngine Android setup (see
// native/hermes/android/build.gradle.kts).
//
// JsEngine is vendored at zipline/native/hermes. We resolve all paths via
// `rootProject.projectDir` (the build root), which makes them stable
// across subproject working directories.
val jsEngineRoot: File =
  File(rootProject.projectDir, "zipline/native/hermes")
val hermesImportCompilers =
  File(jsEngineRoot, "build_host_hermesc/ImportHostCompilers.cmake")

// Helper: pick up JAVA_HOME from the environment, or fall back to
// /usr/libexec/java_home on macOS. AGP's externalNativeBuild doesn't always
// forward env vars to CMake, so we set JAVA_HOME on the relevant tasks.
val javaHome: String? = System.getenv("JAVA_HOME")
  ?: run {
    val os = System.getProperty("os.name").lowercase()
    if (os.contains("mac")) {
      try {
        val p = ProcessBuilder("/usr/libexec/java_home")
          .redirectErrorStream(true).start()
        if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0) {
          p.inputStream.bufferedReader().readText().trim()
        } else null
      } catch (_: Exception) { null }
    } else null
  }

val preBuildHermesHost: TaskProvider<Exec> =
  tasks.register<Exec>("preBuildHermesHost") {
    description = "Pre-build host hermesc and shermes (used by Android cross-build's InternalJavaScript step)."
    group = "build"
    workingDir(jsEngineRoot)
    inputs.dir(jsEngineRoot)
    outputs.file(hermesImportCompilers)
    val cmakeBin = System.getenv("CMAKE_BIN") ?: "cmake"
    val jobs = Runtime.getRuntime().availableProcessors().toString()
    if (javaHome != null) {
      val jh: Any = javaHome!!
      environment("JAVA_HOME" to jh)
    }
    commandLine(
      "sh", "-c",
      """
      if [ ! -f '${hermesImportCompilers.absolutePath}' ]; then
        $cmakeBin -S '${jsEngineRoot.absolutePath}' -B '${jsEngineRoot.absolutePath}/build_host_hermesc' -G Ninja -DCMAKE_BUILD_TYPE=Release
        $cmakeBin --build '${jsEngineRoot.absolutePath}/build_host_hermesc' --target hermesc shermes -j $jobs
      fi
      """.trimIndent()
    )
  }

val buildHermesJni: TaskProvider<Exec> =
  tasks.register<Exec>("buildHermesJni") {
    description = "Build Hermes JNI libraries (full for host JVM, lean for Android)"
    group = "build"
    workingDir(rootProject.projectDir)
    inputs.dir(File(rootProject.projectDir, "zipline/native/hermes-jni-build"))
    outputs.dir(File(rootProject.projectDir, "zipline/src/jvmMain/resources/jni"))
    outputs.dir(File(rootProject.projectDir, "zipline/src/androidMain/resources/jniLibs"))
    val jobs = Runtime.getRuntime().availableProcessors().toString()
    if (javaHome != null) {
      val jh: Any = javaHome!!
      environment("JAVA_HOME" to jh)
    }
    commandLine(
      "sh", "./zipline/host-build.sh"
    )
  }

// Build a merged static Hermes+Zipline archive for a single iOS variant.
// The output libhermesvm.a is embedded in the iOS Kotlin/Native klib.
fun registerBuildHermesStaticIos(
  konanTarget: KonanTarget,
  sdk: String,
  architectures: String,
): TaskProvider<Exec> {
  val lowerName = konanTarget.name
  val buildDir = layout.buildDirectory.dir("hermes-static/$lowerName/cmake").get().asFile
  val outputFile = File(buildDir, "libhermesvm.a")
  val inputFiles = listOf(
    file("native/hermes-core.cpp"),
    file("native/hermes-core.h"),
    file("native/hermes-ios/hermes-ios.cpp"),
    file("native/hermes-ios/hermes-ios.h"),
    file("native/ContextNative.cpp"),
    file("native/ContextNative.h"),
    file("native/ContextBase.cpp"),
    file("native/common/JsIntrinsics.cpp"),
    file("native/hermes-jni-build/CMakeLists.txt"),
  )
  return tasks.register<Exec>("buildHermesStatic${lowerName.replaceUnderscoreCamelCase()}") {
    description = "Build static Hermes archive for ${konanTarget.name}"
    group = "build"
    dependsOn(preBuildHermesHost)
    inputs.dir(jsEngineRoot)
    inputFiles.forEach { inputs.file(it) }
    outputs.file(outputFile)
    val cmakeBin = System.getenv("CMAKE_BIN") ?: "cmake"
    val jobs = Runtime.getRuntime().availableProcessors().toString()
    workingDir(rootProject.projectDir)
    commandLine(
      "sh", "-c",
      """
      set -euo pipefail
      SDK_PATH=$(xcrun --sdk '$sdk' --show-sdk-path)
      CC=$(xcrun --sdk '$sdk' -find clang)
      CXX=$(xcrun --sdk '$sdk' -find clang++)
      $cmakeBin -S '${file("native/hermes-jni-build").absolutePath}' \
        -B '${buildDir.absolutePath}' \
        -G Ninja \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_SYSTEM_NAME=iOS \
        -DCMAKE_C_COMPILER="${'$'}CC" \
        -DCMAKE_CXX_COMPILER="${'$'}CXX" \
        -DCMAKE_OSX_SYSROOT="${'$'}SDK_PATH" \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=14.0 \
        -DCMAKE_OSX_ARCHITECTURES='$architectures' \
        -DHERMESVM_LEAN=ON \
        -DHERMES_IOS_STATIC=ON \
        -DHERMES_SRC='${jsEngineRoot.absolutePath}' \
        -DIMPORT_HOST_COMPILERS='${hermesImportCompilers.absolutePath}'
      $cmakeBin --build '${buildDir.absolutePath}' --target hermesvm_static -j $jobs
      """.trimIndent()
    )
  }
}

// Turn ios_arm64 / ios_x64 / ios_simulator_arm64 into IosArm64 / IosX64 / IosSimulatorArm64.
fun String.replaceUnderscoreCamelCase(): String =
  split("_").joinToString("") { it.replaceFirstChar { ch -> ch.uppercase() } }

val buildHermesStaticIosArm64 =
  registerBuildHermesStaticIos(
    KonanTarget.IOS_ARM64,
    sdk = "iphoneos",
    architectures = "arm64",
  )

val buildHermesStaticIosX64 =
  registerBuildHermesStaticIos(
    KonanTarget.IOS_X64,
    sdk = "iphonesimulator",
    architectures = "x86_64",
  )

val buildHermesStaticIosSimulatorArm64 =
  registerBuildHermesStaticIos(
    KonanTarget.IOS_SIMULATOR_ARM64,
    sdk = "iphonesimulator",
    architectures = "arm64",
  )

// The iOS Kotlin/Native interop tasks need the static archive to exist so
// cinterop can copy it into the hermes.klib.
listOf(
  "cinteropHermesIosArm64" to buildHermesStaticIosArm64,
  "cinteropHermesIosX64" to buildHermesStaticIosX64,
  "cinteropHermesIosSimulatorArm64" to buildHermesStaticIosSimulatorArm64,
).forEach { (taskName, staticTask) ->
  tasks.matching { it.name == taskName }.configureEach { dependsOn(staticTask) }
}

// Host-side (JVM) and Android publications still depend on the host-build.sh
// artifacts. iOS klibs pull in the static archive via the cinterop dependency
// above; they no longer need the dylib staged by host-build.sh.

// Also ensure Hermes JNI libs are built before JVM resource processing and jar tasks
tasks.matching { it.name == "jvmJar" || it.name == "jvmProcessResources" }
  .configureEach { dependsOn(buildHermesJni) }

// Ensure Hermes JNI libs are built before Android resource processing tasks
tasks.matching { it.name.matches(Regex("process.*JavaRes")) }
  .configureEach { dependsOn(buildHermesJni) }

android {
  namespace = "app.cash.zipline"
  compileSdk = libs.versions.compileSdk.get().toInt()

  defaultConfig {
    minSdk = libs.versions.minSdk.get().toInt()
    multiDexEnabled = true

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("proguard-rules.pro")

    ndk {
      abiFilters += listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
    }

    // AGP invokes native/hermes-jni-build/CMakeLists.txt per ABI. That
    // CMakeLists does add_subdirectory(jsEngine) with the Android flags, then
    // adds our glue on top and links the whole thing into a single .so.
    externalNativeBuild {
      cmake {
        arguments(
          "-DANDROID_TOOLCHAIN=clang",
          "-DANDROID_STL=c++_shared",
          // Pass the path to the host-side ImportHostCompilers.cmake so
          // Hermes InternalJavaScript step can invoke hermesc and shermes.
          "-DIMPORT_HOST_COMPILERS=${hermesImportCompilers.absolutePath}",
          // Pass JAVA_HOME for the JNI include path (on non-Apple; on Android
          // the NDK toolchain's sysroot include dir already has jni.h).
          "-DJAVA_HOME=${javaHome ?: ""}",
          // Build lean Hermes (no JIT compiler, ~800KB smaller per ABI).
          // The compile() JNI method will throw UnsupportedOperationException.
          "-DHERMESVM_LEAN=TRUE",
        )
        cFlags("-fstrict-aliasing", "-DCONFIG_VERSION=\\\"${jsEngineVersion()}\\\"")
        cppFlags("-fstrict-aliasing", "-DCONFIG_VERSION=\\\"${jsEngineVersion()}\\\"")
      }
    }

    packaging {
      // We get multiple copies of some license files via JNA, which is a transitive dependency of
      // kotlinx-coroutines-test. Don't fail the build on these duplicates.
      resources {
        excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1")
      }

      // Keep debug symbols to get function names if the JsEngine runtime
      // crashes. The release libzipline_jsengine_jni.so is ~5.5 MB; without
      // debug symbols it's ~3 MB. Application release builds can still
      // strip these away later.
      jniLibs.keepDebugSymbols += "**/libzipline_jsengine_jni.so"
      // Also keep debug symbols for the Hermes VM itself so we can debug
      // runtime crashes in the VM code (e.g., in evaluatePreparedJavaScript).
      jniLibs.keepDebugSymbols += "**/libhermesvmlean.so"

      // fbjni is required by Hermes Android CMakeLists when
      // HERMES_ENABLE_INTL=TRUE. We disable INTL so the linker never
      // pulls fbjni into libzipline_jsengine_jni.so, but we still
      // surface it via prefab so CMake's find_package(fbjni) succeeds
      // unconditionally and resolves to the real upstream package.
      // Note: fbjni is NOT a CMake build target; it's an imported
      // find_package target, so we don't list it in cmake.targets.
    }
    dependencies {
      // Real fbjni from Maven Central (prefab). The stub at
      // native/fbjni-stub/ is no longer needed because Hermes can find
      // the real package via CMAKE_PREFIX_PATH populated by prefab.
      //
      // compileOnly so fbjni's bundled libc++_shared.so (older NDK) is not
      // packaged into the final APK; zipline ships its own libc++_shared.so
      // (matching the NDK used to build our .so).
      // Removed because the real fbjni broke compose-live at runtime.
    }
  }

  // TODO: Remove when https://issuetracker.google.com/issues/260059413 is resolved.
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  sourceSets {
    getByName("androidTest") {
      resources.srcDir("src/androidInstrumentationTest/resources/")
      resources.srcDir(copyTestingJs)
    }
  }

  // The above `resources.srcDir(copyTestingJs)` code is supposed to automatically add a task
  // dependency, but it doesn't. So we add it ourselves using this nonsense.
  afterEvaluate {
    libraryVariants.onEach { libraryVariant ->
      libraryVariant.testVariant?.processJavaResourcesProvider?.configure {
        dependsOn(copyTestingJs)
      }
      // Ensure Hermes JNI libs (lean) are built before resource processing
      libraryVariant.processJavaResourcesProvider?.configure {
        dependsOn(buildHermesJni)
      }
    }
  }

  buildTypes {
    val release by getting {
      externalNativeBuild {
        cmake {
          arguments("-DCMAKE_BUILD_TYPE=MinSizeRel")
          cFlags("-g0", "-Os", "-fomit-frame-pointer", "-DNDEBUG", "-fvisibility=hidden")
          cppFlags("-g0", "-Os", "-fomit-frame-pointer", "-DNDEBUG", "-fvisibility=hidden")
        }
      }
    }
    val debug by getting {
      externalNativeBuild {
        cmake {
          arguments("-DCMAKE_BUILD_TYPE=Debug")
          cFlags("-g", "-DDEBUG", "-DDUMP_LEAKS")
          cppFlags("-g", "-DDEBUG", "-DDUMP_LEAKS")
        }
      }
    }
  }

  externalNativeBuild {
    cmake {
      // Drive native/hermes-jni-build/CMakeLists.txt which does
      // add_subdirectory(jsEngine) + our glue.
      path = file("native/hermes-jni-build/CMakeLists.txt")
    }
  }

  // Make sure the host-side hermesc is built before AGP's externalNativeBuild
  // task. AGP configures+builds in one task, and the host build is a
  // separate Gradle task.
  tasks.matching { it.name.startsWith("externalNativeBuild") }
    .configureEach { dependsOn(preBuildHermesHost) }
  // JAVA_HOME is passed to AGP's CMake invocation via -DJAVA_HOME=... on
  // the externalNativeBuild { cmake { arguments(...) } } block above. We
  // don't need to set it on the Gradle JVM itself.
}

configure<MavenPublishBaseExtension> {
  configure(
    KotlinMultiplatform(javadocJar = JavadocJar.Empty())
  )
}
