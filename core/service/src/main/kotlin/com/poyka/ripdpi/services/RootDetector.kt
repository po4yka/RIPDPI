package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Upper bound for the whole superuser check, so a root prompt nobody answers or a hung `su` cannot hang it. */
internal const val RootCheckTimeoutMs = 15_000L

class RootDetector
    internal constructor(
        private val commandRunner: RootCommandRunner,
        private val checkTimeoutMs: Long = RootCheckTimeoutMs,
    ) {
        @Inject
        constructor() : this(RuntimeRootCommandRunner())

        private companion object {
            private val log = Logger.withTag("RootDetector")
        }

        /**
         * Tests if superuser access is available by executing known su command
         * forms.
         * This triggers the root management app's permission dialog.
         * Only call when the user explicitly enables root mode.
         * A check that does not finish within [RootCheckTimeoutMs] reports [RootAccessResult.Denied]:
         * an `su` that does not answer means a root manager did not grant access.
         */
        suspend fun testRootAccess(): RootAccessResult =
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(checkTimeoutMs) { runAttempts() } ?: run {
                    log.w { "root access check timed out after ${checkTimeoutMs}ms" }
                    RootAccessResult.Denied
                }
            }

        private suspend fun runAttempts(): RootAccessResult {
            val attempts =
                listOf(
                    RootCommandAttempt("su -c id", listOf("su", "-c", "id")),
                    RootCommandAttempt("su 0 id", listOf("su", "0", "id")),
                )
            var sawDenied = false
            for (attempt in attempts) {
                val result =
                    try {
                        // Interruptible, so a cancelled or timed-out check stops the blocked `su` wait.
                        runInterruptible { commandRunner.run(attempt.command) }
                    } catch (e: IOException) {
                        log.w(e) { "root access unavailable via ${attempt.description}" }
                        continue
                    }
                if (result.exitCode == 0 && result.output.contains("uid=0")) {
                    log.i { "root access confirmed via ${attempt.description}: ${result.output}" }
                    return RootAccessResult.Granted
                }
                sawDenied = true
                log.w {
                    "root access denied via ${attempt.description}: " +
                        "exitCode=${result.exitCode} output=${result.output}"
                }
            }
            return if (sawDenied) {
                RootAccessResult.Denied
            } else {
                RootAccessResult.Unavailable
            }
        }
    }

enum class RootAccessResult {
    Granted,
    Denied,
    Unavailable,
}

internal data class RootCommandAttempt(
    val description: String,
    val command: List<String>,
)

internal data class RootCommandResult(
    val exitCode: Int,
    val output: String,
)

internal interface RootCommandRunner {
    @Throws(IOException::class)
    fun run(command: List<String>): RootCommandResult
}

/** Exit code reported for a command that did not exit within its timeout. */
internal const val RootCommandTimedOutExitCode = -1

internal class RuntimeRootCommandRunner(
    private val timeoutMs: Long = RootCheckTimeoutMs,
) : RootCommandRunner {
    override fun run(command: List<String>): RootCommandResult {
        val process = Runtime.getRuntime().exec(command.toTypedArray())
        try {
            // Wait first: the output of `id` is small, and the wait can time out or be interrupted.
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                return RootCommandResult(exitCode = RootCommandTimedOutExitCode, output = "")
            }
            val output =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            return RootCommandResult(exitCode = process.exitValue(), output = output)
        } finally {
            // A timed-out or interrupted wait leaves `su` running; never leak it.
            if (process.isAlive) process.destroyForcibly()
            process.errorStream.close()
        }
    }
}
