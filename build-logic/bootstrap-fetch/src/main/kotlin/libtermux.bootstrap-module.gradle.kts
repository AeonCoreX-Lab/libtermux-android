/**
 * LibTermux-Android — build-logic
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 *
 * Precompiled convention plugin shared by every bootstrap-<abi> module.
 * Each module's own build.gradle.kts applies this plugin plus
 * `com.libtermux.bootstrap-fetch`, then only sets its own ABI-specific
 * bootstrapFetch { } values — everything else (AGP config, publishing,
 * wireBinariesToBuild) lives here exactly once.
 */
import com.android.build.api.dsl.LibraryExtension
import org.gradle.kotlin.dsl.configure

plugins {
    id("com.android.library")
}

configure<LibraryExtension> {
    namespace = "com.libtermux.bootstrap"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        // No source code, no native build of our own — this module's sole
        // job is shipping prebuilt binaries under src/main/jniLibs/<abi>/.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Make sure binaries are fetched before every build that needs jniLibs —
// AGP picks up src/main/jniLibs/<abi>/*.so automatically, it just needs to
// exist before the merge-native-libs task reads the directory.
tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn("fetchBootstrapBinaries") }
tasks.matching { it.name == "preBuild" }
    .configureEach { dependsOn("fetchBootstrapBinaries") }
