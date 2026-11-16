import com.android.build.api.variant.HostTestBuilder.Companion.UNIT_TEST_TYPE
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
  kotlin("multiplatform")
  id("com.android.library")
  kotlin("plugin.serialization")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("app.cash.sqldelight")
  id("binary-compatibility-validator")
}

kotlin {
  jvm()
  androidTarget {
    publishLibraryVariants("release")
  }
  if (false) {
    linuxX64()
  }
  if (ZiplineHost.canBuildApple) {
    macosX64()
    macosArm64()
    iosArm64()
    iosX64()
    iosSimulatorArm64()
    tvosArm64()
    tvosSimulatorArm64()
    tvosX64()
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    val commonMain by getting {
      dependencies {
        api(libs.kotlinx.coroutines.core)
        api(projects.zipline)
        api(libs.okio.core)
        api(libs.sqldelight.runtime)
        implementation(libs.kotlinx.serialization.json)
      }
    }
    val jniMain by creating {
      dependsOn(commonMain)
      dependencies {
        implementation(libs.okHttp.core)
      }
    }
    val jvmMain by getting {
      dependsOn(jniMain)
      dependencies {
        implementation(libs.sqldelight.driver.sqlite)
        implementation(libs.sqlite.jdbc)
      }
    }
    val androidMain by getting {
      dependsOn(jniMain)
      dependencies {
        implementation(libs.sqldelight.driver.android)
      }
    }
    if (ZiplineHost.canBuildApple) {
      val nativeMain by getting {
        dependencies {
          implementation(libs.sqldelight.driver.native)
        }
      }
    }

    val commonTest by getting {
      dependencies {
        implementation(libs.assertk)
        implementation(kotlin("test"))
        implementation(projects.ziplineLoaderTesting)
        implementation(projects.ziplineTesting)
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.turbine)
      }
    }
    val androidInstrumentedTest by getting {
      dependsOn(commonTest)
    }
    val jniTest by creating {
      dependsOn(commonTest)
      dependencies {
        implementation(libs.junit)
        implementation(libs.okHttp.mockWebServer)
        implementation(libs.okio.fakeFileSystem)
        implementation(libs.sqldelight.driver.sqlite)
        implementation(libs.sqlite.jdbc)
      }
    }
    val jvmTest by getting {
      dependsOn(jniTest)
      dependencies {
        implementation(projects.ziplineLoaderTesting)
      }
    }

    if (ZiplineHost.canBuildApple) {
      val nativeTest by getting {
        dependencies {
          implementation(projects.ziplineLoaderTesting)
        }
      }
    }
  }
}


android {
  namespace = "app.cash.zipline.loader"
  compileSdk = libs.versions.compileSdk.get().toInt()

  defaultConfig {
    minSdk = libs.versions.minSdk.get().toInt()
    multiDexEnabled = true
  }

  // TODO: Remove when https://issuetracker.google.com/issues/260059413 is resolved.
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  sourceSets {
    getByName("androidTest") {
      java.srcDirs("src/androidInstrumentedTest/kotlin/")
    }
  }
}

androidComponents {
  beforeVariants { variant ->
    variant.hostTests[UNIT_TEST_TYPE]?.enable = false
  }
}

sqldelight {
  databases {
    create("Database") {
      packageName.set("app.cash.zipline.loader.internal.cache")
    }
  }
}

configure<MavenPublishBaseExtension> {
  configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
}

// macOS binaries link :zipline's host libhermesvm.dylib, which is not part of the klib. Point the
// linker (and the test runtime) at it and make sure it is built first.
kotlin.targets.withType<KotlinNativeTarget>().configureEach {
  val (hostLibDir, buildTask) = when (konanTarget) {
    KonanTarget.MACOS_ARM64 -> "macos-arm64" to ":zipline:buildHermesHostMacosArm64"
    KonanTarget.MACOS_X64 -> "macos-x86_64" to ":zipline:buildHermesHostMacosX86_64"
    else -> return@configureEach
  }
  binaries.all {
    linkerOpts += listOf(
      "-L${rootDir}/zipline/build/hermes-jni/$hostLibDir",
      "-rpath", "${rootDir}/zipline/build/hermes-jni/$hostLibDir",
    )
    linkTaskProvider.configure { dependsOn(buildTask) }
  }
}
