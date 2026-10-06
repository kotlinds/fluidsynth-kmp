import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import java.io.File

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven)
}

mavenPublishing {
    // Empty javadoc jars: embedding the full Dokka site in each platform publication
    // would blow the Maven Central size limits. KDoc stays available in the IDE via sources jars.
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("fluidsynth-kmp")
        description.set("Kotlin Multiplatform wrapper for FluidSynth")
        url.set(project.ext.get("url")?.toString())
        licenses {
            license {
                name.set(project.ext.get("license.name")?.toString())
                url.set(project.ext.get("license.url")?.toString())
            }
        }
        developers {
            developer {
                id.set(project.ext.get("developer.id")?.toString())
                name.set(project.ext.get("developer.name")?.toString())
                email.set(project.ext.get("developer.email")?.toString())
                url.set(project.ext.get("developer.url")?.toString())
            }
        }
        scm {
            url.set(project.ext.get("scm.url")?.toString())
        }
    }
}

val libsDir = File(rootDir, "libs")
val includeDir = File(libsDir, "include")

// Static libraries built by scripts/build-ios-static.sh
val iosStaticDir = File(libsDir, "ios-static")
val iosArm64StaticDir = File(iosStaticDir, "arm64")
val iosSimulatorStaticDir = File(iosStaticDir, "arm64_x86_64-simulator")
val hasIosArm64Lib = File(iosArm64StaticDir, "libfluidsynth.a").exists()
val hasIosSimulatorLib = File(iosSimulatorStaticDir, "libfluidsynth.a").exists()

// XCFramework headers are still used for cinterop (same version as static build)
val xcframeworkBase = libsDir.walk().maxDepth(3).find { it.name == "FluidSynth.xcframework" }
val iosArm64HeadersDir = xcframeworkBase?.resolve("ios-arm64/FluidSynth.framework/Headers")
val iosSimulatorHeadersDir = xcframeworkBase?.resolve("ios-arm64_x86_64-simulator/FluidSynth.framework/Headers")

android {
    namespace = "dev.kotlinds.fluidsynthkmp"
    ndkVersion = "30.0.16248370"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
        }
    }
    sourceSets["main"].jniLibs.srcDirs("src/androidMain/jniLibs")
}

kotlin {
    // Tiers are in accordance with <https://kotlinlang.org/docs/native-target-support.html>
    // Tier 1
    // macOS: link against system-installed FluidSynth (Homebrew: /opt/homebrew/lib or /usr/local/lib)
    macosArm64 {
        val main by compilations.getting
        main.cinterops.create("fluidsynth") {
            definitionFile = file("src/nativeInterop/cinterop/fluidsynth-macos.def")
            includeDirs.headerFilterOnly(includeDir)
            extraOpts("-libraryPath", "/opt/homebrew/lib")
        }
    }

    // iOS targets: statically linked — libfluidsynth.a is embedded in the klib.
    // Run scripts/build-ios-static.sh once to populate libs/ios-static/ before publishing.
    iosSimulatorArm64 {
        if (hasIosSimulatorLib) {
            val main by compilations.getting
            main.cinterops.create("fluidsynth") {
                definitionFile = file("src/nativeInterop/cinterop/fluidsynth-ios-simulator.def")
                includeDirs.headerFilterOnly(iosSimulatorHeadersDir!!)
                extraOpts("-libraryPath", iosSimulatorStaticDir.absolutePath)
            }
        }
    }

    // Tier 2
    // Linux: link against system libfluidsynth
    linuxX64 {
        val main by compilations.getting
        main.cinterops.create("fluidsynth") {
            definitionFile = file("src/nativeInterop/cinterop/fluidsynth-dynamic.def")
            includeDirs.headerFilterOnly(includeDir)
        }
    }
    linuxArm64 {
        val main by compilations.getting
        main.cinterops.create("fluidsynth") {
            definitionFile = file("src/nativeInterop/cinterop/fluidsynth-dynamic.def")
            includeDirs.headerFilterOnly(includeDir)
        }
    }
    iosArm64 {
        if (hasIosArm64Lib) {
            val main by compilations.getting
            main.cinterops.create("fluidsynth") {
                definitionFile = file("src/nativeInterop/cinterop/fluidsynth-ios-arm64.def")
                includeDirs.headerFilterOnly(iosArm64HeadersDir!!)
                extraOpts("-libraryPath", iosArm64StaticDir.absolutePath)
            }
        }
    }

    // Tier 3
    // Windows: link against system/bundled fluidsynth DLL
    mingwX64 {
        val main by compilations.getting
        main.cinterops.create("fluidsynth") {
            definitionFile = file("src/nativeInterop/cinterop/fluidsynth-dynamic.def")
            includeDirs.headerFilterOnly(includeDir)
        }
    }

    // Android
    androidTarget {
        publishLibraryVariants("release")
    }

    // jvm
    jvmToolchain(21)
    jvm {
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.jna)
        }

        // nativeMain covers all native targets: macOS + Linux + Windows + iOS
        val nativeMain by creating { dependsOn(commonMain.get()) }
        val macosArm64Main by getting { dependsOn(nativeMain) }
        val linuxX64Main by getting { dependsOn(nativeMain) }
        val linuxArm64Main by getting { dependsOn(nativeMain) }
        val mingwX64Main by getting { dependsOn(nativeMain) }
        val iosArm64Main by getting { dependsOn(nativeMain) }
        val iosSimulatorArm64Main by getting { dependsOn(nativeMain) }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("${rootProject.projectDir}/detekt.yml")
    source.from(file("src/commonMain/kotlin"))
}
