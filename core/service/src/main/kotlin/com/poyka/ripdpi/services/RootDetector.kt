package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

class RootDetector
    @Inject
    constructor() {
        private companion object {
            private val log = Logger.withTag("RootDetector")
        }

        /**
         * Tests if superuser access is available by executing `su -c id`.
         * This triggers the root management app's permission dialog.
         * Only call when the user explicitly enables root mode.
         */
        suspend fun testRootAccess(): RootAccessResult =
            withContext(Dispatchers.IO) {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                    val output =
                        process.inputStream
                            .bufferedReader()
                            .readText()
                            .trim()
                    process.errorStream.close()
                    val exitCode = process.waitFor()
                    if (exitCode == 0 && output.contains("uid=0")) {
                        log.i { "root access confirmed: $output" }
                        RootAccessResult.Granted
                    } else {
                        log.w { "root access denied: exitCode=$exitCode output=$output" }
                        RootAccessResult.Denied
                    }
                } catch (e: IOException) {
                    log.w(e) { "root access unavailable" }
                    RootAccessResult.Unavailable
                }
            }
    }

enum class RootAccessResult {
    Granted,
    Denied,
    Unavailable,
}
