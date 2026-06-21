package com.poyka.ripdpi.services

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val RelayProcessStopTimeoutMs = 1_500L

internal class SubprocessRelayProcessSupervisor(
    private val outputParser: SubprocessRelayOutputParser,
) {
    @Volatile private var process: Process? = null

    @Volatile private var processOutputThread: Thread? = null

    fun start(
        processBuilder: ProcessBuilder,
        spec: SubprocessSocksRelayLaunchSpec,
        onOutputEvent: (SubprocessRelayOutputEvent) -> Unit,
    ): Process {
        val activeProcess = processBuilder.start()
        process = activeProcess
        try {
            spec.standardInput?.let { payload ->
                activeProcess.outputStream.use { input ->
                    input.write(payload)
                    input.flush()
                }
            }
        } catch (error: IOException) {
            activeProcess.destroyForcibly()
            process = null
            throw error
        }
        processOutputThread = startProcessOutputThread(activeProcess, spec, onOutputEvent)
        return activeProcess
    }

    fun stop(): String? {
        processOutputThread?.interrupt()
        processOutputThread = null
        val activeProcess = process
        process = null
        if (activeProcess == null) {
            return null
        }
        return try {
            activeProcess.destroy()
            if (!activeProcess.waitFor(RelayProcessStopTimeoutMs, TimeUnit.MILLISECONDS)) {
                activeProcess.destroyForcibly()
                activeProcess.waitFor(RelayProcessStopTimeoutMs, TimeUnit.MILLISECONDS)
            }
            null
        } catch (error: IOException) {
            error.message
        } catch (error: SecurityException) {
            error.message
        }
    }

    suspend fun waitForExit(): Int = process?.waitFor() ?: 0

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
}
