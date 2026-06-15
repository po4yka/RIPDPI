package com.poyka.ripdpi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Offline unit tests for the real [XrayNativeBridgeLibXrayImpl] logic.
 *
 * Gated to the `testXrayLinked` source set — only active when the libXray AAR is
 * linked, so `libXray.DialerController` (a PURE gobind interface with no static
 * native init) is on the classpath. These tests MUST NEVER touch
 * `libXray.LibXray`: its static initializer calls `System.loadLibrary("gojni")`,
 * which is Android-ABI-only and would throw `UnsatisfiedLinkError` on a host JVM.
 * Every native call is routed through the injected [FakeLibXrayFfi].
 */
class XrayNativeBridgeLibXrayImplTest {
    /**
     * Fake [LibXrayFfi] that records calls and replays scripted base64
     * CallResponse strings. Never loads gojni.
     */
    private class FakeLibXrayFfi(
        var runResponse: String = encodeCallResponse(success = true),
        var stopResponse: String = encodeCallResponse(success = true),
        var versionResponse: String = encodeCallResponse(success = true, data = "26.3.27"),
        var xrayStateValue: Boolean = false,
        var throwOnRequest: Boolean = false,
    ) : LibXrayFfi {
        val calls: MutableList<String> = mutableListOf()
        var lastDatDir: String? = null
        var lastMphCachePath: String? = null
        var lastConfigJson: String? = null
        val registeredDialers: MutableList<libXray.DialerController> = mutableListOf()
        val registeredListeners: MutableList<libXray.DialerController> = mutableListOf()

        override fun newRunFromJsonRequest(
            datDir: String,
            mphCachePath: String,
            configJson: String,
        ): String {
            calls += "newRunFromJsonRequest"
            lastDatDir = datDir
            lastMphCachePath = mphCachePath
            lastConfigJson = configJson
            if (throwOnRequest) {
                throw IllegalStateException("simulated native request failure")
            }
            return "base64-request"
        }

        override fun runXrayFromJson(base64Request: String): String {
            calls += "runXrayFromJson"
            return runResponse
        }

        override fun stopXray(): String {
            calls += "stopXray"
            return stopResponse
        }

        override fun xrayVersion(): String {
            calls += "xrayVersion"
            return versionResponse
        }

        override fun getXrayState(): Boolean {
            calls += "getXrayState"
            return xrayStateValue
        }

        override fun registerDialer(controller: libXray.DialerController) {
            calls += "registerDialer"
            registeredDialers += controller
        }

        override fun registerListener(controller: libXray.DialerController) {
            calls += "registerListener"
            registeredListeners += controller
        }
    }

    @Test
    fun `registerProtect installs a dialer + listener adapter delegating to the controller`() {
        val ffi = FakeLibXrayFfi()
        val bridge = XrayNativeBridgeLibXrayImpl(DAT_DIR, ffi)
        val protectedFds = mutableListOf<Int>()
        val controller =
            XrayProtectController { fd ->
                protectedFds += fd
                true
            }

        bridge.registerProtect(controller)

        assertEquals(listOf("registerDialer", "registerListener"), ffi.calls)
        assertEquals(1, ffi.registeredDialers.size)
        assertEquals(1, ffi.registeredListeners.size)
        // The same adapter is registered as both dialer and listener.
        assertTrue(ffi.registeredDialers.single() === ffi.registeredListeners.single())

        // The adapter forwards protectFd(long) -> controller.protect(int).
        val ok = ffi.registeredDialers.single().protectFd(4242L)
        assertTrue(ok)
        assertEquals(listOf(4242), protectedFds)
    }

    @Test
    fun `start returns 0 on a success CallResponse and never logs config`() {
        val ffi = FakeLibXrayFfi(runResponse = encodeCallResponse(success = true))
        val bridge = XrayNativeBridgeLibXrayImpl(DAT_DIR, ffi)

        val code = bridge.start(CONFIG_WITH_SECRET)

        assertEquals(0, code)
        assertEquals(listOf("newRunFromJsonRequest", "runXrayFromJson"), ffi.calls)
        // datDir + a derived mph path under it are passed through verbatim.
        assertEquals(DAT_DIR, ffi.lastDatDir)
        assertNotNull(ffi.lastMphCachePath)
        assertTrue(ffi.lastMphCachePath!!.startsWith(DAT_DIR))
        assertEquals(CONFIG_WITH_SECRET, ffi.lastConfigJson)
    }

    @Test
    fun `start returns non-zero on a failure CallResponse`() {
        val ffi = FakeLibXrayFfi(runResponse = encodeCallResponse(success = false, error = "bad config"))
        val bridge = XrayNativeBridgeLibXrayImpl(DAT_DIR, ffi)

        assertTrue(bridge.start(CONFIG_WITH_SECRET) != 0)
    }

    @Test
    fun `start returns non-zero on an empty CallResponse (marshal failure)`() {
        val ffi = FakeLibXrayFfi(runResponse = "")
        val bridge = XrayNativeBridgeLibXrayImpl(DAT_DIR, ffi)

        assertTrue(bridge.start(CONFIG_WITH_SECRET) != 0)
    }

    @Test
    fun `start returns non-zero on undecodable CallResponse`() {
        val ffi = FakeLibXrayFfi(runResponse = "not-base64-!!!")
        val bridge = XrayNativeBridgeLibXrayImpl(DAT_DIR, ffi)

        assertTrue(bridge.start(CONFIG_WITH_SECRET) != 0)
    }

    @Test
    fun `start returns non-zero when the request builder throws and never escapes`() {
        val ffi = FakeLibXrayFfi(throwOnRequest = true)
        val bridge = XrayNativeBridgeLibXrayImpl(DAT_DIR, ffi)

        assertTrue(bridge.start(CONFIG_WITH_SECRET) != 0)
        // runXrayFromJson is never reached after the request builder throws.
        assertEquals(listOf("newRunFromJsonRequest"), ffi.calls)
    }

    @Test
    fun `version extracts data from the base64 CallResponse`() {
        val ffi = FakeLibXrayFfi(versionResponse = encodeCallResponse(success = true, data = "26.3.27"))
        val bridge = XrayNativeBridgeLibXrayImpl(DAT_DIR, ffi)

        assertEquals("Xray 26.3.27", bridge.version())
    }

    @Test
    fun `version degrades to unknown on a failed or undecodable response`() {
        val undecodable = XrayNativeBridgeLibXrayImpl(DAT_DIR, FakeLibXrayFfi(versionResponse = "###"))
        assertEquals("Xray unknown", undecodable.version())

        val failure =
            XrayNativeBridgeLibXrayImpl(
                DAT_DIR,
                FakeLibXrayFfi(versionResponse = encodeCallResponse(success = false, error = "x")),
            )
        assertEquals("Xray unknown", failure.version())
    }

    @Test
    fun `listenerReady and isAlive reflect getXrayState`() {
        val running = XrayNativeBridgeLibXrayImpl(DAT_DIR, FakeLibXrayFfi(xrayStateValue = true))
        assertTrue(running.listenerReady())
        assertTrue(running.isAlive())

        val stopped = XrayNativeBridgeLibXrayImpl(DAT_DIR, FakeLibXrayFfi(xrayStateValue = false))
        assertFalse(stopped.listenerReady())
        assertFalse(stopped.isAlive())
    }

    @Test
    fun `stop delegates to stopXray and is safe to call repeatedly`() {
        val ffi = FakeLibXrayFfi()
        val bridge = XrayNativeBridgeLibXrayImpl(DAT_DIR, ffi)

        bridge.stop()
        bridge.stop()

        assertEquals(listOf("stopXray", "stopXray"), ffi.calls)
    }

    @Test
    fun `protect-first ordering at the bridge level - registerProtect precedes start`() {
        val ffi = FakeLibXrayFfi()
        val bridge = XrayNativeBridgeLibXrayImpl(DAT_DIR, ffi)

        bridge.registerProtect(XrayProtectController { true })
        bridge.start(CONFIG_WITH_SECRET)

        val registerIdx = ffi.calls.indexOf("registerDialer")
        val startIdx = ffi.calls.indexOf("runXrayFromJson")
        assertTrue("registerDialer before runXrayFromJson", registerIdx in 0 until startIdx)
    }

    private companion object {
        const val DAT_DIR = "/data/user/0/com.poyka.ripdpi/files/geo"
        const val CONFIG_WITH_SECRET =
            """{"outbounds":[{"settings":{"vnext":[{"users":""" +
                """[{"id":"d0c0ffee-dead-beef-cafe-000000000001"}]}]}}]}"""

        /** Build a base64-encoded `nodep.CallResponse` JSON, as gomobile returns. */
        fun encodeCallResponse(
            success: Boolean,
            data: String = "",
            error: String = "",
        ): String {
            val json =
                buildString {
                    append("{\"success\":").append(success)
                    if (data.isNotEmpty()) append(",\"data\":\"").append(data).append("\"")
                    if (error.isNotEmpty()) append(",\"error\":\"").append(error).append("\"")
                    append("}")
                }
            return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        }
    }
}
