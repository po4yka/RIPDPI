package com.poyka.ripdpi.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class XrayNativeBridgeStubTest {
    @Test
    fun `unavailable startup releases ownership and permits another session`() =
        runBlocking {
            val owner = XrayRuntimeOwner(XrayNativeBridgeLibXrayImpl("unused-assets"))
            repeat(2) {
                val runtime = RipDpiXrayRuntime(owner)
                try {
                    runtime.start("{}", XrayProtectController { true })
                    fail("Expected unavailable Xray startup to fail")
                } catch (_: XrayRuntimeException.StartupFailed) {
                    // The unavailable engine must fail without holding native ownership.
                }
                assertFalse(owner.isOccupied)
                assertEquals(StopCause.AlreadyStopped, runtime.stop())
                assertEquals(StopCause.AlreadyStopped, runtime.stop())
            }
        }
}
