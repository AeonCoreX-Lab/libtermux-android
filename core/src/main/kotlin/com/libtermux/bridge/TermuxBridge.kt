/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * * http://www.apache.org/licenses/LICENSE-2.0
 * * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/libtermux-android
 */
package com.libtermux.bridge

import com.libtermux.executor.CommandExecutor
import com.libtermux.executor.ExecutionResult
import com.libtermux.executor.OutputLine
import com.libtermux.fs.VirtualFileSystem
import com.libtermux.pkg.PackageManager
import com.libtermux.pkg.PkgResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * High-level API bridge between Kotlin/Java application code
 * and the Linux environment.
 *
 * This is the primary entry-point for developers using the library.
 *
 * Example:
 * ```kotlin
 * val result = bridge.run("echo Hello World")
 * val json   = bridge.runJson("python3 -c 'import json; print(json.dumps({\"x\":1}))'")
 * bridge.python("""
 * import math
 * print(math.sqrt(144))
 * """)
 * ```
 */
class TermuxBridge internal constructor(
    @PublishedApi internal val executor: CommandExecutor, // <-- FIXED HERE
    private val pkgManager: PackageManager,
    val vfs: VirtualFileSystem,
) {

    // ── Shell Commands ────────────────────────────────────────────────────

    /** Run a shell command and get the full result */
    suspend fun run(
        command: String,
        workDir: File? = null,
        env: Map<String, String> = emptyMap(),
    ): ExecutionResult = executor.execute(command, workDir, env)

    /** Run and return stdout string (throws on non-zero exit) */
    suspend fun runOrThrow(command: String): String {
        val result = executor.execute(command)
        if (result.isFailure) throw TermuxCommandException(command, result)
        return result.stdout
    }

    /** Run and stream output lines */
    fun runStreaming(
        command: String,
        workDir: File? = null,
    ): Flow<OutputLine> = executor.executeStreaming(command, workDir)

    /** Run and stream only text lines (stdout) */
    fun runStreamingText(command: String): Flow<String> =
        runStreaming(command).map { line ->
            when (line) {
                is OutputLine.Stdout -> line.text
                is OutputLine.Stderr -> "[ERR] ${line.text}"
                is OutputLine.Exit   -> "[Exit: ${line.code}]"
            }
        }

    // ── JSON Support ──────────────────────────────────────────────────────

    /** Run command and parse stdout as JSON */
    suspend fun runJson(command: String): JsonElement {
        val result = executor.execute(command)
        if (result.isFailure) throw TermuxCommandException(command, result)
        return Json.parseToJsonElement(result.stdout.trim())
    }

    /** Run command and parse into typed object */
    suspend inline fun <reified T> runAs(command: String): T {
        val result = executor.execute(command)
        if (result.isFailure) throw TermuxCommandException(command, result)
        return Json.decodeFromString(result.stdout.trim())
    }

    // ── Language Runners ──────────────────────────────────────────────────

    /** Execute a Python script string */
    suspend fun python(
        script: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
    ): ExecutionResult = executor.executePython(script, args, env)

    /** Execute a Node.js script string */
    suspend fun node(
        script: String,
        env: Map<String, String> = emptyMap(),
    ): ExecutionResult = executor.executeNode(script, env)

    /** Execute a Bash script string */
    suspend fun bash(
        script: String,
        env: Map<String, String> = emptyMap(),
    ): ExecutionResult {
        val f = File(vfs.tmpDir, "bash_${System.currentTimeMillis()}.sh")
        f.writeText("#!/data/data/com.termux/files/usr/bin/bash\n$script")
        return try {
            executor.executeScript(f, extraEnv = env)
        } finally { f.delete() }
    }

    /** Execute a Ruby script string */
    suspend fun ruby(
        script: String,
        env: Map<String, String> = emptyMap(),
    ): ExecutionResult = run("ruby -e '${script.replace("'", "\\'")}'", env = env)

    /** Execute a Perl script string */
    suspend fun perl(
        script: String,
        env: Map<String, String> = emptyMap(),
    ): ExecutionResult = run("perl -e '${script.replace("'", "\\'")}'", env = env)

    // ── File Operations ───────────────────────────────────────────────────

    /** Write a file into HOME and optionally execute it */
    fun writeFile(path: String, content: String): File =
        vfs.writeHomeFile(path, content)

    /** Write an executable script into PREFIX/bin */
    fun writeScript(name: String, content: String): File =
        vfs.writeScript(name, content)

    /** Read a file from HOME */
    fun readFile(path: String): String? =
        runCatching { File(vfs.homeDir, path).readText() }.getOrNull()

    // ── Package Management ────────────────────────────────────────────────

    /** Install packages */
    suspend fun install(vararg packages: String): PkgResult =
        pkgManager.install(*packages)

    /** Uninstall packages */
    suspend fun uninstall(vararg packages: String): PkgResult =
        pkgManager.uninstall(*packages)

    /** Update all packages */
    suspend fun upgradeAll(): PkgResult =
        pkgManager.upgradeAll()

    /** Install pip packages */
    suspend fun pipInstall(vararg packages: String): PkgResult =
        pkgManager.pipInstall(*packages)

    /** Install npm packages globally */
    suspend fun npmInstall(vararg packages: String): PkgResult =
        pkgManager.npmInstall(*packages)

    // ── Utilities ────────────────────────────────────────────────────────

    /** Check if a tool/binary is available */
    suspend fun hasCommand(command: String): Boolean =
        executor.hasBinary(command)

    /** Get current PATH */
    suspend fun getPath(): String =
        executor.execute("echo \$PATH").stdout.trim()

    /** Get disk usage of environment */
    fun getDiskUsage(): String = vfs.diskUsageString()

    /** Get environment variables as map */
    fun getEnvironment(): Map<String, String> = vfs.buildEnv()
}

/** Thrown when a command exits with non-zero code */
class TermuxCommandException(
    val command: String,
    val result: ExecutionResult,
) : Exception("Command '$command' failed with exit ${result.exitCode}: ${result.stderr}")
