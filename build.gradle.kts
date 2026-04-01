/**
 * LibTermux-Android — Root Build Script
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 *
 * Licensed under the Apache License, Version 2.0
 */
plugins {
    alias(libs.plugins.android.application)  apply false
    alias(libs.plugins.android.library)      apply false
    alias(libs.plugins.kotlin.android)       apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.dokka)                apply false
    alias(libs.plugins.maven.publish)        apply false
}

// Fix: use layout.buildDirectory instead of deprecated rootProject.buildDir
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}