package com.poyka.ripdpi.services

import kotlinx.coroutines.yield
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val RelayProcessStopTimeoutMs = 1_500L
private const val ManagedPtGracefulStopTimeoutMs = 500L

internal class SubprocessRelayProcessSupervisor(
    private val outputParser: SubprocessRelayOutputParser,
    private val startProcess: (ProcessBuilder) -> Process = { builder -> builder.start() },
) {
    @Volatile private var process: Process? = null

    @Volatile private var processOutputThread: Thread? = null

    @Volatile private var processErrorThread: Thread? = null

    private val outputLock = Any()
    private var outputOwner: Any? = null
    private var closeStdinOnStop = false

    @Synchronized
    fun start(
        processBuilder: ProcessBuilder,
        spec: SubprocessSocksRelayLaunchSpec,
        onOutputEvent: (SubprocessRelayOutputEvent) -> Unit,
    ): Process {
        check(process == null) { "Previous subprocess is still owned by the supervisor" }
        synchronized(outputLock) { outputParser.reset() }
        val activeProcess = startProcess(processBuilder)
        process = activeProcess
        closeStdinOnStop = spec.managedClientBridge != null
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
        val owner = Any()
        synchronized(outputLock) { outputOwner = owner }
        processOutputThread = startProcessOutputThread(activeProcess, owner, spec, onOutputEvent)
        if (spec.managedClientBridge != null) {
            processErrorThread = startManagedErrorThread(activeProcess, spec.runtimeKind)
        }
        return activeProcess
    }

    @Synchronized
    fun stop(): String? {
        // Keep both readers draining while the owned process handles EOF and exits.
        synchronized(outputLock) { outputOwner = null }
        val activeProcess = process
        if (activeProcess == null) {
            processOutputThread = null
            processErrorThread = null
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
            error.message ?: "I/O error while stopping subprocess"
        } catch (error: SecurityException) {
            error.message ?: "Access denied while stopping subprocess"
        }
    }

    suspend fun waitForExit(): Int {
        val activeProcess = process ?: return 0
        // Relay runtimes start undispatched on an IO worker, where withContext(IO) never suspends;
        // yield first so the blocking wait cannot hold the caller's thread and stall the VPN start.
        yield()
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
        owner: Any,
        spec: SubprocessSocksRelayLaunchSpec,
        onOutputEvent: (SubprocessRelayOutputEvent) -> Unit,
    ): Thread =
        thread(
            name = "subprocess-relay-output-${spec.runtimeKind}",
            isDaemon = true,
        ) {
            try {
                activeProcess.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line =
                            if (spec.managedClientBridge ==
                                null
                            ) {
                                reader.readLine()
                            } else {
                                readManagedPtLine(reader)
                            }
                        if (line == null) break
                        // Fence both parser state and delivery against stop and the next run's reset.
                        synchronized(outputLock) {
                            if (outputOwner === owner) outputParser.parse(line, spec)?.let(onOutputEvent)
                        }
                    }
                }
            } catch (error: ManagedPtControlLineException) {
                synchronized(outputLock) {
                    if (outputOwner === owner) {
                        onOutputEvent(
                            SubprocessRelayOutputEvent.Error(
                                spec.runtimeKind,
                                "managed_pt_startup",
                                error.message.orEmpty(),
                            ),
                        )
                    }
                }
            } catch (_: IOException) {
                // Process.destroy() closes stdout under the blocked read: that is EOF.
            }
        }

    private fun startManagedErrorThread(
        activeProcess: Process,
        runtimeKind: String,
    ): Thread =
        thread(name = "subprocess-relay-stderr-$runtimeKind", isDaemon = true) {
            try {
                activeProcess.errorStream.use { input ->
                    // Stderr is diagnostic text, not PT control. Discard it in bounded chunks;
                    // retaining or parsing it could expose secrets or alter another run's state.
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (input.read(buffer) != -1) Unit
                }
            } catch (_: IOException) {
                // Termination can close the pipe while the drain is blocked.
            }
        }

    private fun terminate(activeProcess: Process): Boolean {
        val gracefulWait = if (closeStdinOnStop) ManagedPtGracefulStopTimeoutMs else 0L
        if (gracefulWait > 0) {
            try {
                activeProcess.outputStream.close()
            } catch (_: IOException) {
                // A closed stdin must not prevent termination of the owned process.
            }
            if (activeProcess.waitFor(gracefulWait, TimeUnit.MILLISECONDS)) return true
        }
        activeProcess.destroy()
        // The EOF phase shares the existing stop budget with normal termination.
        return if (activeProcess.waitFor(RelayProcessStopTimeoutMs - gracefulWait, TimeUnit.MILLISECONDS)) {
            true
        } else {
            activeProcess.destroyForcibly()
            activeProcess.waitFor(RelayProcessStopTimeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    private fun release(activeProcess: Process) {
        if (process === activeProcess) {
            synchronized(outputLock) { outputOwner = null }
            process = null
            closeStdinOnStop = false
            processOutputThread?.interrupt()
            processErrorThread?.interrupt()
            processOutputThread = null
            processErrorThread = null
        }
    }
}

private const val ManagedPtLineLimit = 8_192

private class ManagedPtControlLineException(
    message: String,
) : IOException(message)

internal fun readManagedPtLine(reader: java.io.Reader): String? {
    val line = StringBuilder()
    while (true) {
        val next = reader.read()
        if (next == -1) {
            if (line.isNotEmpty()) throw ManagedPtControlLineException("Unterminated managed PT control line")
            return null
        }
        if (next == '\n'.code) return line.toString().removeSuffix("\r")
        if (line.length ==
            ManagedPtLineLimit
        ) {
            throw ManagedPtControlLineException("Managed PT control line exceeds limit")
        }
        line.append(next.toChar())
    }
}
