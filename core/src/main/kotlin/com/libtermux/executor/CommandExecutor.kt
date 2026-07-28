/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/libtermux-android
 */
package com.libtermux.executor

import com.libtermux.TermuxConfig
import com.libtermux.fs.VirtualFileSystem
import com.libtermux.utils.FileUtils.chmodExecutable
import com.libtermux.utils.FileUtils.makeExecutable
import com.libtermux.utils.NativeUtils
import com.libtermux.utils.TermuxLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

/**
 * Core process executor that runs commands inside the VFS Linux environment.
 *
 * Supports both standard ProcessBuilder execution and native PTY-backed
 * interactive execution (via JNI) for full terminal emulation.
 */
class CommandExecutor(
    private val config: TermuxConfig,
    private val vfs: VirtualFileSystem,
) {

    /**
     * Execute a command and return a complete [ExecutionResult].
     *
     * @param command   Shell command string (e.g. "python3 -c 'print(1+1)'")
     * @param workDir   Working directory (defaults to HOME)
     * @param extraEnv  Extra environment variables merged on top of VFS env
     * @param shell     Shell binary name in PREFIX/bin (default: bash)
     */
    suspend fun execute(
        command: String,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
        shell: String = "bash",
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        TermuxLogger.d("exec: $command")

        val result = withTimeoutOrNull(config.maxCommandTimeoutMs) {
            runProcess(command, workDir, extraEnv, shell)
        } ?: ExecutionResult(
            stdout          = "",
            stderr          = "Command timed out after ${config.maxCommandTimeoutMs}ms",
            exitCode        = -1,
            executionTimeMs = config.maxCommandTimeoutMs,
            command         = command,
        )

        TermuxLogger.d("exit=${result.exitCode} time=${result.executionTimeMs}ms")
        result
    }

    /**
     * Execute a command and stream output line by line via [OutputLine].
     */
    fun executeStreaming(
        command: String,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
        shell: String = "bash",
    ): Flow<OutputLine> = flow {
        TermuxLogger.d("stream exec: $command")
        val proc = buildProcess(command, workDir, extraEnv, shell)

        val stdoutReader = BufferedReader(InputStreamReader(proc.inputStream))
        val stderrReader = BufferedReader(InputStreamReader(proc.errorStream))

        val stdoutLines = mutableListOf<String>()
        val stderrLines = mutableListOf<String>()

        val stdoutThread = Thread { stdoutReader.forEachLine { stdoutLines.add(it) } }
        val stderrThread = Thread { stderrReader.forEachLine { stderrLines.add(it) } }
        stdoutThread.start()
        stderrThread.start()

        try {
            stdoutThread.join(config.maxCommandTimeoutMs)
            stderrThread.join(1000)

            stdoutLines.forEach { emit(OutputLine.Stdout(it)) }
            stderrLines.forEach { emit(OutputLine.Stderr(it)) }

            val exit = proc.waitFor()
            emit(OutputLine.Exit(exit))
        } finally {
            runCatching { stdoutThread.interrupt() }
            runCatching { stderrThread.interrupt() }
            proc.destroyForcibly()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Execute a Python script string directly.
     */
    suspend fun executePython(
        script: String,
        args: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecutionResult {
        val scriptFile = File(vfs.tmpDir, "script_${System.currentTimeMillis()}.py")
        scriptFile.writeText(script)
        val argStr = args.joinToString(" ")
        return try {
            execute("python3 ${scriptFile.absolutePath} $argStr".trim(), extraEnv = extraEnv)
        } finally {
            scriptFile.delete()
        }
    }

    /**
     * Execute a Node.js script string directly.
     */
    suspend fun executeNode(
        script: String,
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecutionResult {
        val scriptFile = File(vfs.tmpDir, "script_${System.currentTimeMillis()}.js")
        scriptFile.writeText(script)
        return try {
            execute("node ${scriptFile.absolutePath}", extraEnv = extraEnv)
        } finally {
            scriptFile.delete()
        }
    }

    /**
     * Execute a shell script file.
     */
    suspend fun executeScript(
        scriptFile: File,
        args: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
        shell: String = "bash",
    ): ExecutionResult {
        scriptFile.setExecutable(true, false)
        val argStr = args.joinToString(" ")
        return execute(
            command  = "${scriptFile.absolutePath} $argStr".trim(),
            extraEnv = extraEnv,
            shell    = shell,
        )
    }

    /**
     * Test if a binary exists in PREFIX/bin.
     */
    suspend fun hasBinary(name: String): Boolean {
        val result = execute("which $name")
        return result.isSuccess && result.stdout.isNotBlank()
    }

    /**
     * Resolve a bootstrap binary name (e.g. "bash", "proot", "tar") to its
     * executable [File] path, using the same resolution order as command
     * execution: [TermuxConfig.bootstrapProvider] first, legacy
     * `vfs.binDir` fallback otherwise.
     *
     * Other modules (e.g. `:os`'s ProotRunner, which needs `proot` and
     * `tar` directly rather than through [execute]) should call this
     * instead of touching `vfs.binDir` themselves, so there is exactly one
     * place that understands binary resolution.
     */
    fun resolveBinary(name: String): File {
        val provider = config.bootstrapProvider
        if (provider != null) return provider.binaryPath(name)
        return File(vfs.binDir, name)
    }

    /** True if [name] is available to execute, via provider or legacy binDir. */
    fun hasBundledBinary(name: String): Boolean {
        val provider = config.bootstrapProvider
        return if (provider != null) provider.hasBinary(name) else File(vfs.binDir, name).exists()
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun runProcess(
        command: String,
        workDir: File?,
        extraEnv: Map<String, String>,
        shell: String,
    ): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val proc = buildProcess(command, workDir, extraEnv, shell)
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val exit   = proc.waitFor()
        return ExecutionResult(
            stdout          = stdout.trimEnd(),
            stderr          = stderr.trimEnd(),
            exitCode        = exit,
            executionTimeMs = System.currentTimeMillis() - startTime,
            command         = command,
        )
    }

    /**
     * Build and start a [Process] for the given shell command.
     *
     * Binary resolution order:
     *   1. If [TermuxConfig.bootstrapProvider] is set, resolve [shell] through
     *      it — these binaries live under `nativeLibraryDir`, extracted by
     *      PackageManager at install time, and are always executable. No
     *      chmod recovery is needed or meaningful for this path.
     *   2. Otherwise, fall back to the legacy `vfs.binDir` (filesDir) lookup.
     *      This only works on Android 9 (API 28) and below — on API 29+,
     *      Android refuses to exec a file this app wrote into its own
     *      private storage, regardless of permission bits, and the
     *      chmod-and-retry below cannot fix that (it fixes a *different*,
     *      much rarer case: bits genuinely not set by an older install).
     */
    private fun buildProcess(
        command: String,
        workDir: File?,
        extraEnv: Map<String, String>,
        shell: String,
    ): Process {
        val provider = config.bootstrapProvider
        val env = vfs.buildEnv(extraEnv)

        if (provider != null) {
            if (!provider.hasBinary(shell)) {
                throw IOException(
                    "Binary '$shell' not bundled by the configured BootstrapProvider. " +
                    "Check your bootstrap-<abi> dependency matches this device's ABI."
                )
            }
            val shellBin = provider.binaryPath(shell).absolutePath
            return startProcess(shellBin, command, workDir, env)
        }

        val shellBin = File(vfs.binDir, shell).let {
            if (it.exists()) it.absolutePath else "/system/bin/sh"
        }

        return try {
            startProcess(shellBin, command, workDir, env)
        } catch (e: IOException) {
            if (e.isPermissionDenied()) {
                TermuxLogger.w(
                    "Permission denied launching $shellBin — this is expected on " +
                    "Android 10+ (API 29+) for binaries downloaded into app-private " +
                    "storage; chmod cannot fix it. Configure TermuxConfig.bootstrapProvider " +
                    "with a bootstrap-<abi> artifact instead. Attempting one chmod retry " +
                    "in case bits were simply never set (older-install edge case)."
                )
                chmodExecutable(File(shellBin))
                makeExecutable(vfs.binDir)
                try {
                    startProcess(shellBin, command, workDir, env)
                } catch (retryEx: IOException) {
                    TermuxLogger.e("Retry also failed for $shellBin", retryEx)
                    throw retryEx
                }
            } else {
                throw e
            }
        }
    }

    private fun startProcess(
        shellBin: String,
        command: String,
        workDir: File?,
        env: Map<String, String>,
    ): Process =
        ProcessBuilder(shellBin, "-c", command)
            .directory(workDir ?: vfs.homeDir)
            .also { pb ->
                pb.environment().clear()
                pb.environment().putAll(env)
            }
            .redirectErrorStream(false)
            .start()

    /**
     * Returns true if this IOException is an EACCES / Permission denied error.
     * Java wraps the OS errno as "error=13" in the exception message.
     */
    private fun IOException.isPermissionDenied(): Boolean =
        message?.contains("error=13") == true ||
        message?.contains("Permission denied") == true
}
