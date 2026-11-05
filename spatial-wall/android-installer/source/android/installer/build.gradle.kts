// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
import java.util.Properties

plugins { id("com.android.application") version "9.4.1" }

val repository = rootProject.projectDir.resolve("../..").canonicalFile
val localProps = Properties().apply {
    val source = repository.resolve("android/local.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}
val sharedKeystore = localProps.getProperty("nyan.keystore")?.let { file(it) }?.takeIf { it.exists() }
if (providers.gradleProperty("nyan.requireReleaseSigning").orNull == "true" && sharedKeystore == null)
    throw GradleException("The product signing key is required to publish the installer")

val installerAssetsDir = layout.buildDirectory.dir("sharedAssets")
val installerResourcesDir = layout.buildDirectory.dir("sharedResources")
val installerSourcesDir = layout.buildDirectory.dir("sharedSources")
val installerTestsDir = layout.buildDirectory.dir("sharedTests")
// The public-source export uses the same allowlist; never copy the full app tree.
val sharedClasses = file("shared-java.txt").readLines().filter { it.isNotBlank() }
check(sharedClasses.all { it.matches(Regex("[A-Za-z][A-Za-z0-9]*")) } && sharedClasses.distinct() == sharedClasses)
val sharedTests = file("shared-tests.txt").readLines().filter { it.isNotBlank() }
check(sharedTests.all { it.matches(Regex("[A-Za-z][A-Za-z0-9]*")) } && sharedTests.distinct() == sharedTests)

android {
    namespace = "jp.nyan.spatialwall.probe"
    buildFeatures.buildConfig = true
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "jp.nyanreal.spatialwall.remote"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild.cmake.arguments += "-DANDROID_STL=c++_static"
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild.cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
    signingConfigs {
        if (sharedKeystore != null) create("shared") {
            storeFile = sharedKeystore
            storePassword = localProps.getProperty("nyan.keystore.password") ?: "android"
            keyAlias = localProps.getProperty("nyan.keystore.alias") ?: "spatialwall"
            keyPassword = storePassword
        }
    }
    buildTypes {
        debug { if (sharedKeystore != null) signingConfig = signingConfigs.getByName("shared") }
        release { if (sharedKeystore != null) signingConfig = signingConfigs.getByName("shared") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main") {
        java.directories.add(installerSourcesDir.get().asFile.absolutePath)
        assets.directories.add(installerAssetsDir.get().asFile.absolutePath)
        res.directories.add(installerResourcesDir.get().asFile.absolutePath)
    }
    sourceSets.getByName("test").java.directories.add(installerTestsDir.get().asFile.absolutePath)
}

val stageInstallerSources = tasks.register<Sync>("stageInstallerSources") {
    from(repository.resolve("android/app/src/main/java")) {
        include(sharedClasses.map { "jp/nyan/spatialwall/probe/$it.java" })
    }
    into(installerSourcesDir)
}
val stageInstallerTests = tasks.register<Sync>("stageInstallerTests") {
    from(repository.resolve("android/app/src/test/java")) {
        include(sharedTests.map { "jp/nyan/spatialwall/probe/$it.java" })
    }
    into(installerTestsDir)
}
val stageInstallerAssets = tasks.register<Sync>("stageInstallerAssets") {
    from(file("LICENSE")) { into("licenses"); rename { "Spatial-Wall-installer-MIT.txt" } }
    from(repository.resolve("licenses/boringssl.txt")) { into("licenses") }
    val sdk = localProps.getProperty("sdk.dir") ?: System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
        ?: throw GradleException("Android SDK is required")
    val prebuilt = file(sdk).resolve("ndk/28.2.13676358/toolchains/llvm/prebuilt")
    val notice = prebuilt.listFiles()?.map { it.resolve("NOTICE") }?.singleOrNull { it.isFile }
        ?: throw GradleException("The fixed Android NDK runtime notice is missing")
    from(notice) { into("licenses"); rename { "LLVM-runtime-NOTICE.txt" } }
    into(installerAssetsDir)
}
val stageInstallerResources = tasks.register<Sync>("stageInstallerResources") {
    from(repository.resolve("android/app/src/main/res")) {
        include("mipmap*/**", "values/ic_launcher_background.xml", "values/themes.xml", "xml/data_extraction_rules.xml",
                "values/strings-update.xml", "values-ja/strings-update.xml")
    }
    into(installerResourcesDir)
}
tasks.named("preBuild") { dependsOn(stageInstallerSources, stageInstallerTests, stageInstallerAssets, stageInstallerResources) }

dependencies { testImplementation("junit:junit:4.13.2") }
