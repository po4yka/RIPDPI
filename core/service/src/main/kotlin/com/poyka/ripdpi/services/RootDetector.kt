package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

class RootDetector
    internal constructor(
        private val commandRunner: RootCommandRunner,
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
         */
        suspend fun testRootAccess(): RootAccessResult =
            withContext(Dispatchers.IO) {
                val attempts =
                    listOf(
                        RootCommandAttempt("su -c id", listOf("su", "-c", "id")),
                        RootCommandAttempt("su 0 id", listOf("su", "0", "id")),
                    )
                var sawDenied = false
                for (attempt in attempts) {
                    val result =
                        try {
                            commandRunner.run(attempt.command)
                        } catch (e: IOException) {
                            log.w(e) { "root access unavailable via ${attempt.description}" }
                            continue
                        }
                    if (result.exitCode == 0 && result.output.contains("uid=0")) {
                        log.i { "root access confirmed via ${attempt.description}: ${result.output}" }
                        return@withContext RootAccessResult.Granted
                    }
                    sawDenied = true
                    log.w {
                        "root access denied via ${attempt.description}: " +
                            "exitCode=${result.exitCode} output=${result.output}"
                    }
                }
                if (sawDenied) {
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

private class RuntimeRootCommandRunner : RootCommandRunner {
    override fun run(command: List<String>): RootCommandResult {
        val process = Runtime.getRuntime().exec(command.toTypedArray())
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        process.errorStream.close()
        return RootCommandResult(
            exitCode = process.waitFor(),
            output = output,
        )
    }
}
