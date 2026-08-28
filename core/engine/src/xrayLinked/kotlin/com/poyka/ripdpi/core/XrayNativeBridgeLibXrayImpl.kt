package com.poyka.ripdpi.core

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * Internal seam over the gomobile `libXray.LibXray` static facade.
 *
 * Only [GomobileLibXrayFfi] touches `libXray.LibXray`, whose static initializer
 * triggers `System.loadLibrary` for the Android-only `gojni` native library.
 * Keeping every `LibXray.*` call behind this interface lets the pure-Kotlin
 * parsing and protect-adapter logic in [XrayNativeBridgeLibXrayImpl] be
 * unit-tested on a host JVM with a fake — the production path never classloads
 * `LibXray` until it runs on a device.
 */
internal interface LibXrayFfi {
    /**
     * Build the (base64) run request. Mirrors
     * `String LibXray.newXrayRunFromJSONRequest(datDir, mphCachePath, configJSON)`,
     * which is declared `throws Exception`.
     */
    @Throws(Exception::class)
    fun newRunFromJsonRequest(
        datDir: String,
        mphCachePath: String,
        configJson: String,
    ): String

    /** Mirrors `String LibXray.runXrayFromJSON(base64Text)` — base64 CallResponse. */
    fun runXrayFromJson(base64Request: String): String

    /** Mirrors `String LibXray.stopXray()` — base64 CallResponse. */
    fun stopXray(): String

    /** Mirrors `String LibXray.xrayVersion()` — base64 CallResponse with version in `data`. */
    fun xrayVersion(): String

    /** Mirrors `boolean LibXray.getXrayState()` — run-state liveness. */
    fun getXrayState(): Boolean

    fun initDns(
        controller: libXray.DialerController,
        server: String,
    )

    fun resetDns()

    /** Mirrors `void LibXray.registerDialerController(DialerController)`. */
    fun registerDialer(controller: libXray.DialerController)

    /** Mirrors `void LibXray.registerListenerController(DialerController)`. */
    fun registerListener(controller: libXray.DialerController)
}

/**
 * Production [LibXrayFfi] that delegates to the gomobile-bound `libXray.LibXray`.
 *
 * This is the ONLY class that references `libXray.LibXray`; loading it triggers
 * the gojni native library load and therefore must never run on a host JVM.
 */
internal class GomobileLibXrayFfi : LibXrayFfi {
    @Throws(Exception::class)
    override fun newRunFromJsonRequest(
        datDir: String,
        mphCachePath: String,
        configJson: String,
    ): String = libXray.LibXray.newXrayRunFromJSONRequest(datDir, mphCachePath, configJson)

    override fun runXrayFromJson(base64Request: String): String = libXray.LibXray.runXrayFromJSON(base64Request)

    override fun stopXray(): String = libXray.LibXray.stopXray()

    override fun xrayVersion(): String = libXray.LibXray.xrayVersion()

    override fun getXrayState(): Boolean = libXray.LibXray.getXrayState()

    override fun initDns(
        controller: libXray.DialerController,
        server: String,
    ) {
        libXray.LibXray.initDns(controller, server)
    }

    override fun resetDns() = libXray.LibXray.resetDns()

    override fun registerDialer(controller: libXray.DialerController) {
        libXray.LibXray.registerDialerController(controller)
    }

    override fun registerListener(controller: libXray.DialerController) {
        libXray.LibXray.registerListenerController(controller)
    }
}

/**
 * Real [XrayNativeBridge] backed by the gomobile libXray AAR.
 *
 * Compiled only in the `xrayLinked` source set (when the AAR is linked); the
 * `xrayStub` variant defines the same FQN with a throwing body for offline
 * builds. The native-touching calls go through [LibXrayFfi] so the parsing and
 * protect-adapter logic is offline-unit-testable with a fake.
 *
 * ### Protect-first contract
 * [registerProtect] installs a [libXray.DialerController] adapter via BOTH
 * `registerDialerController` and `registerListenerController`, and is invoked by
 * [RipDpiXrayRuntime] BEFORE [start]. See
 * `.claude/rules/vpnservice-protect-invariant.md`.
 *
 * The source-patched AAR fails socket creation on protection denial. All native
 * calls run exclusively on [XrayRuntimeOwner]'s process-owned worker.
 *
 * @param datDir absolute path to the geoip/geosite `.dat` asset directory libXray
 *   reads at start (never contains secrets).
 * @param ffi seam over `libXray.LibXray`; defaults to the production
 *   [GomobileLibXrayFfi]. Tests inject a fake to avoid the gojni native load.
 */
class XrayNativeBridgeLibXrayImpl internal constructor(
    private val datDir: String,
    private val ffi: LibXrayFfi,
) : XrayNativeBridge {
    /** Public production constructor — uses the gomobile-backed FFI. */
    constructor(datDir: String) : this(datDir, GomobileLibXrayFfi())

    /**
     * Stable MPH-cache target under [datDir]. xray-core falls back to the raw
     * `.dat` when no cache is present; this gives a future `BuildMphCache` step a
     * fixed location without an extra Hilt binding.
     */
    private val mphCachePath: String = File(datDir, MPH_CACHE_FILE_NAME).absolutePath

    private val protectController = AtomicReference<XrayProtectController?>()
    private var dialerRegistered = false
    private var listenerRegistered = false
    private var inboundPort: Int? = null
    private val adapter =
        object : libXray.DialerController {
            override fun protectFd(fd: Long): Boolean {
                if (fd !in 0..Int.MAX_VALUE.toLong()) return false
                val current = protectController.get() ?: return false
                return runCatching { current.protect(fd.toInt()) }.getOrDefault(false) &&
                    protectController.get() === current
            }
        }

    override fun registerProtect(controller: XrayProtectController) {
        protectController.set(controller)
        // Xray-core appends controllers. Install one forwarding adapter for process lifetime.
        if (!dialerRegistered) {
            ffi.registerDialer(adapter)
            dialerRegistered = true
        }
        if (!listenerRegistered) {
            ffi.registerListener(adapter)
            listenerRegistered = true
        }
    }

    override fun start(jsonConfig: String): Int {
        // Never log jsonConfig — it carries UUIDs / REALITY private keys.
        return try {
            check(protectController.get() != null) { "Xray protection is not registered" }
            val config = XrayBridgeConfig.parse(jsonConfig)
            inboundPort = config.inboundPort
            // Relay endpoints are resolved by the service's eligible-underlay callback.
            // Any remaining Go system lookup is denied before socket I/O, without plaintext fallback.
            ffi.initDns(
                object : libXray.DialerController {
                    override fun protectFd(fd: Long): Boolean = false
                },
                "127.0.0.1:53",
            )
            val request = ffi.newRunFromJsonRequest(datDir, mphCachePath, jsonConfig)
            val response = ffi.runXrayFromJson(request)
            if (parseCallResponseSuccess(response)) START_OK else START_REJECTED
        } catch (_: Exception) {
            // newXrayRunFromJSONRequest is `throws Exception`; any native failure
            // is a rejected start, not a thrown bridge error.
            START_REJECTED
        }
    }

    override fun stop() {
        protectController.set(null)
        check(parseCallResponseSuccess(ffi.stopXray())) { "Xray native cleanup failed" }
        ffi.resetDns()
        inboundPort = null
    }

    override fun version(): String {
        val data = runCatching { decodeCallResponseData(ffi.xrayVersion()) }.getOrNull()
        return "Xray " + (data?.takeIf(String::isNotBlank) ?: "unknown")
    }

    override fun listenerReady(): Boolean {
        val port = inboundPort ?: return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), ReadinessTimeoutMillis)
                socket.soTimeout = ReadinessTimeoutMillis
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                socket.getInputStream().read() == 5 && socket.getInputStream().read() == 0
            }
        }.getOrDefault(false)
    }

    override fun isAlive(): Boolean = runCatching { ffi.getXrayState() }.getOrDefault(false)

    /**
     * libXray `nodep.CallResponse[T]` — gomobile returns this base64-encoded.
     * The error key is `error` (omitempty), not `err`.
     */
    @Serializable
    private data class CallResponse(
        val success: Boolean = false,
        val data: String = "",
        @SerialName("error") val error: String = "",
    )

    /** Decode a base64 CallResponse; an empty string is a marshal failure. */
    private fun parseCallResponse(base64Text: String): CallResponse? {
        if (base64Text.isEmpty()) return null
        return runCatching {
            val json = String(Base64.getDecoder().decode(base64Text), Charsets.UTF_8)
            JSON.decodeFromString(CallResponse.serializer(), json)
        }.getOrNull()
    }

    /** True only when the response decodes and reports `success`. */
    private fun parseCallResponseSuccess(base64Text: String): Boolean = parseCallResponse(base64Text)?.success == true

    /** The `data` field of a successful CallResponse, or null on any failure. */
    private fun decodeCallResponseData(base64Text: String): String? =
        parseCallResponse(base64Text)?.takeIf(CallResponse::success)?.data

    private companion object {
        const val ReadinessTimeoutMillis = 200
        const val START_OK = 0
        const val START_REJECTED = 1
        const val MPH_CACHE_FILE_NAME = "geo.mph"
        val JSON = RipDpiJson
    }
}
