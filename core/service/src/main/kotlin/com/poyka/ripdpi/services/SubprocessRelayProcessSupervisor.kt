package com.poyka.ripdpi.services

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val RelayProcessStopTimeoutMs = 1_500L

internal class SubprocessRelayProcessSupervisor(
    private val outputParser: SubprocessRelayOutputParser,
    private val startProcess: (ProcessBuilder) -> Process = { builder -> builder.start() },
) {
    @Volatile private var process: Process? = null

    @Volatile private var processOutputThread: Thread? = null

    @Synchronized
    fun start(
        processBuilder: ProcessBuilder,
        spec: SubprocessSocksRelayLaunchSpec,
        onOutputEvent: (SubprocessRelayOutputEvent) -> Unit,
    ): Process {
        check(process == null) { "Previous subprocess is still owned by the supervisor" }
        val activeProcess = startProcess(processBuilder)
        process = activeProcess
        try {
            spec.standardInput?.let { payload ->
                activeProcess.outputStream.use { input ->
                    input.write(payload)
                    input.flush()
                }
            }
        } catch (error: IOException) {
            runCatching { terminate(activeProcess) }
                .onSuccess { exited -> if (exited) release(activeProcess) }
                .onFailure(error::addSuppressed)
            throw error
        }
        processOutputThread = startProcessOutputThread(activeProcess, spec, onOutputEvent)
        return activeProcess
    }

    @Synchronized
    fun stop(): String? {
        processOutputThread?.interrupt()
        val activeProcess = process
        if (activeProcess == null) {
            processOutputThread = null
            return null
        }
        return try {
            if (terminate(activeProcess)) {
                release(activeProcess)
                null
            } else {
                "Subprocess did not exit after forced termination"
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            error.message ?: "Interrupted while waiting for subprocess exit"
        } catch (error: IOException) {
            error.message
        } catch (error: SecurityException) {
            error.message
        }
    }

    suspend fun waitForExit(): Int {
        val activeProcess = process ?: return 0
        val exitCode = activeProcess.waitFor()
        release(activeProcess)
        return exitCode
    }

    fun isRunning(): Boolean {
        val activeProcess = process ?: return false
        return runCatching {
            activeProcess.exitValue()
            false
        }.getOrDefault(true)
    }

    private fun startProcessOutputThread(
        activeProcess: Process,
        spec: SubprocessSocksRelayLaunchSpec,
        onOutputEvent: (SubprocessRelayOutputEvent) -> Unit,
    ): Thread =
        thread(
            name = "subprocess-relay-output-${spec.runtimeKind}",
            isDaemon = true,
        ) {
            activeProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    outputParser.parse(line, spec)?.let(onOutputEvent)
                }
            }
        }

    private fun terminate(activeProcess: Process): Boolean {
        activeProcess.destroy()
        if (activeProcess.waitFor(RelayProcessStopTimeoutMs, TimeUnit.MILLISECONDS)) return true
        activeProcess.destroyForcibly()
        return activeProcess.waitFor(RelayProcessStopTimeoutMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun release(activeProcess: Process) {
        if (process === activeProcess) {
            process = null
            processOutputThread = null
        }
    }
}
