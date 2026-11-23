package com.poyka.ripdpi.core

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.poyka.ripdpi.core.testing.FakeXrayNativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class XrayStartupLoggingTest {
    @Test
    fun `bridge exceptions log the failed phase and omit the native error details`() =
        runTest {
            val events = mutableListOf<Triple<String, String, Throwable?>>()
            val previousWriters = Logger.mutableConfig.logWriterList
            try {
                Logger.setLogWriters(
                    object : LogWriter() {
                        override fun log(
                            severity: Severity,
                            message: String,
                            tag: String,
                            throwable: Throwable?,
                        ) {
                            if (severity == Severity.Error) events += Triple(tag, message, throwable)
                        }
                    },
                )
                val protect = XrayProtectController { true }
                for (failDuringRegistration in listOf(true, false)) {
                    val bridge =
                        object : FakeXrayNativeBridge() {
                            override fun registerProtect(controller: XrayProtectController) {
                                if (failDuringRegistration) throw SecurityException("sensitive protect error")
                            }

                            override fun start(jsonConfig: String): Int = error("sensitive start error: $jsonConfig")
                        }
                    val runtime = RipDpiXrayRuntime(XrayRuntimeOwner(bridge, Dispatchers.Unconfined))
                    val failure = runCatching { runtime.start("sensitive native config", protect) }.exceptionOrNull()
                    assertEquals(-1, (failure as XrayRuntimeException.StartupFailed).code)
                }
            } finally {
                Logger.setLogWriters(previousWriters)
            }

            assertEquals(
                listOf(
                    Triple("Engine", "Xray native start failed: phase=register_protect error=SecurityException", null),
                    Triple("Engine", "Xray native start failed: phase=start error=IllegalStateException", null),
                ),
                events,
            )
        }
}
