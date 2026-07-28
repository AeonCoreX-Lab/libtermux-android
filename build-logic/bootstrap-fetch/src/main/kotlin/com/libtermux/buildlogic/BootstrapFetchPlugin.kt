/**
 * LibTermux-Android — build-logic
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.libtermux.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Registers a `bootstrapFetch { }` extension and a `fetchBootstrapBinaries`
 * task on the applying project (a `bootstrap-<abi>` module).
 *
 * ## What this does and why
 * Termux bootstrap binaries downloaded into `filesDir` at *runtime* can
 * never be executed on Android 10+ (API 29+) — see
 * `core/bootstrap/BootstrapProvider.kt` for the full explanation. The fix
 * is to make binaries part of the APK's native library set, which Android's
 * own installer extracts with execute permission intact. This task performs
 * the download+prepare step at *build* time instead of runtime, writing
 * results straight into `src/main/jniLibs/<abi>/`.
 *
 * ## Known tradeoff: symlinks are resolved, not preserved
 * `jniLibs/` is a flat bag of `.so` files — Android's packager does not
 * preserve symlink structure the way a normal zip extraction would. The
 * Termux bootstrap leans on symlinks heavily (busybox applets all point at
 * one busybox binary, `sh` -> `dash`/`bash`, etc.). This task resolves every
 * symlink in SYMLINKS.txt to a real copy of its target at fetch time. This
 * means:
 *   - Applet-style binaries (busybox's ~200 symlinked commands) each become
 *     a full copy, not a symlink — this noticeably increases APK size
 *     versus the runtime-download approach. That's the real cost of this
 *     fix; there is no way to avoid it while keeping binaries executable.
 *   - Only binaries actually needed for exec should be listed in
 *     [BootstrapFetchExtension.binaries] — non-executable bootstrap content
 *     (usr/share, usr/etc config files, docs) is NOT something this task
 *     handles, since only *execution* is restricted, not file storage. Ship
 *     that content the old way (bundled as an asset, or still downloaded to
 *     filesDir at runtime) if you need it.
 */
class BootstrapFetchPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create<BootstrapFetchExtension>("bootstrapFetch")

        project.tasks.register<FetchBootstrapBinariesTask>("fetchBootstrapBinaries") {
            group = "libtermux"
            description = "Downloads the Termux bootstrap zip for the configured ABI and " +
                "prepares jniLibs/<abi>/lib*.so from it."

            abi.set(ext.abi)
            termuxArch.set(ext.termuxArch)
            bootstrapTag.set(ext.bootstrapTag)
            binaries.set(ext.binaries)
            outputDir.set(project.layout.projectDirectory.dir("src/main/jniLibs"))
            gradleUserHomeDir.set(project.layout.dir(project.provider { project.gradle.gradleUserHomeDir }))
        }
    }
}

abstract class BootstrapFetchExtension {
    /** Android ABI folder name, e.g. "arm64-v8a". Used as the jniLibs subfolder. */
    abstract val abi: Property<String>

    /** Termux's own arch name for the bootstrap zip filename, e.g. "aarch64". */
    abstract val termuxArch: Property<String>

    /**
     * Bootstrap release tag, e.g. "bootstrap-2026.05.24-r1+apt.android-7".
     * Keep this in sync with core's BootstrapReleaseResolver fallback tag —
     * they should generally point at the same release.
     */
    abstract val bootstrapTag: Property<String>

    /**
     * Binary names (as they appear in PREFIX/bin after symlink resolution)
     * to extract into jniLibs. Defaults to the set :core and :os actually
     * exec directly: bash (shell), apt/dpkg (package queries core exposes),
     * busybox (coreutils), proot and tar (needed by :os's ProotRunner).
     * Add to this list only if you exec something else directly — most
     * commands (ls, grep, python3, ...) are run *through* bash -c "...",
     * so bash is the only binary that actually needs to be jniLibs-resolved
     * for a typical `executor.execute("...")` call to work.
     */
    abstract val binaries: org.gradle.api.provider.SetProperty<String>
}

@org.gradle.api.tasks.CacheableTask
abstract class FetchBootstrapBinariesTask : DefaultTask() {

    @get:Input abstract val abi: Property<String>
    @get:Input abstract val termuxArch: Property<String>
    @get:Input abstract val bootstrapTag: Property<String>
    @get:Input abstract val binaries: org.gradle.api.provider.SetProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    /**
     * Gradle user home dir, used only to locate the shared bootstrap-zip
     * cache. Captured at configuration time (see [BootstrapFetchPlugin])
     * because `Task.project` cannot be touched during task execution under
     * the configuration cache.
     */
    @get:org.gradle.api.tasks.Internal abstract val gradleUserHomeDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val abiName = abi.get()
        val arch = termuxArch.get()
        val tag = bootstrapTag.get()
        val wanted = binaries.get()
        val destDir = outputDir.get().dir(abiName).asFile.also { it.mkdirs() }

        val cacheDir = File(gradleUserHomeDir.get().asFile, "libtermux-bootstrap-cache")
        cacheDir.mkdirs()
        val cachedZip = File(cacheDir, "bootstrap-$arch-$tag.zip")

        if (!cachedZip.exists()) {
            val encodedTag = tag.replace("+", "%2B")
            val url = "https://github.com/termux/termux-packages/releases/download/" +
                "$encodedTag/bootstrap-$arch.zip"
            logger.lifecycle("Downloading Termux bootstrap for $arch: $url")
            downloadTo(url, cachedZip)
        } else {
            logger.lifecycle("Using cached bootstrap zip: ${cachedZip.absolutePath}")
        }

        // Extract into a scratch dir first — we need SYMLINKS.txt processed
        // before we know which real files each wanted binary resolves to.
        val scratchDir = File(temporaryDir, "extracted").apply {
            deleteRecursively()
            mkdirs()
        }
        extractZip(cachedZip, scratchDir)

        val symlinks = resolveSymlinkMap(File(scratchDir, "SYMLINKS.txt"))

        // Clean previous outputs for this ABI so removed binaries don't linger.
        destDir.listFiles()?.forEach { it.delete() }

        var packaged = 0
        for (name in wanted) {
            val sourceFile = locateBinary(scratchDir, name, symlinks)
            if (sourceFile == null) {
                logger.warn("bootstrap-fetch: binary '$name' not found in bootstrap for $arch — skipped.")
                continue
            }
            val target = File(destDir, "lib$name.so")
            sourceFile.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
            packaged++
        }

        logger.lifecycle("bootstrap-fetch: packaged $packaged/${wanted.size} binaries into ${destDir.absolutePath}")
        check(packaged > 0) {
            "bootstrap-fetch produced zero binaries for $abiName — check `binaries` and bootstrapTag are correct."
        }
    }

    /**
     * SYMLINKS.txt lines look like `target<TAB>linkPath` (relative to PREFIX).
     * Returns a map of linkPath (e.g. "bin/sh") -> target (e.g. "bin/bash").
     * Mirrors the parsing in core/utils/FileUtils.processSymlinks — kept as
     * a separate build-time implementation since this module can't depend
     * on :core (core depends on Android; this is a plain JVM plugin).
     */
    private fun resolveSymlinkMap(symlinksFile: File): Map<String, String> {
        if (!symlinksFile.exists()) return emptyMap()
        val map = mutableMapOf<String, String>()
        symlinksFile.forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachLine
            val parts = when {
                line.contains("\u2190") -> line.split("\u2190").map { it.trim() }
                line.contains("\u2192") -> line.split("\u2192").map { it.trim() }.reversed()
                else -> line.trim().split(Regex("[ \t]+"))
            }
            if (parts.size >= 2) map[parts[1]] = parts[0]
        }
        return map
    }

    /**
     * Find [name] under PREFIX/bin, following symlink resolution if it's
     * listed in SYMLINKS.txt. Termux symlinks are not chained more than one
     * level in practice (applet -> busybox), but this follows up to a few
     * hops defensively.
     *
     * ## Zip layout
     * `create_bootstrap_archive()` in termux-packages `cd`s into
     * `$PREFIX` (i.e. `.../usr`) *before* zipping — see
     * termux-packages/scripts/generate-bootstraps.sh. So zip entries and
     * SYMLINKS.txt paths are relative to `$PREFIX` itself: `bin/bash`,
     * `SYMLINKS.txt` at the zip root — there is no leading `usr/` inside
     * the archive (unlike the on-device install path
     * `$HOME/../usr/bin/bash`, which is a different, later concept).
     */
    private fun locateBinary(scratchDir: File, name: String, symlinks: Map<String, String>): File? {
        var relPath = "bin/$name"
        var direct = File(scratchDir, relPath)
        var hops = 0
        while (!direct.exists() && hops < 5) {
            val linked = symlinks[relPath] ?: return null
            relPath = linked.removePrefix("./")
            direct = File(scratchDir, relPath)
            hops++
        }
        return direct.takeIf { it.exists() && it.isFile }
    }

    private fun downloadTo(urlStr: String, dest: File) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "LibTermux-Android-BuildLogic/1.0")
        conn.instanceFollowRedirects = true
        conn.connect()
        check(conn.responseCode in 200..299) {
            "Failed to download bootstrap zip: HTTP ${conn.responseCode} from $urlStr"
        }
        dest.parentFile.mkdirs()
        conn.inputStream.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
    }

    private fun extractZip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
