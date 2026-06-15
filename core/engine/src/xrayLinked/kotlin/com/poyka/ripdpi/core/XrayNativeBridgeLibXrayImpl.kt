package com.poyka.ripdpi.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

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
 * Note: the adapter forwards the boolean from `protectFd`, but whether libXray
 * actually fails the socket closed on a *denial* is UNVERIFIED from the gomobile
 * binding alone (a device/B-3 smoke item vs the contract-test fake, which does
 * honor denial). Protect-first *ordering* is honored and load-bearing.
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

    @Volatile
    private var protectController: XrayProtectController? = null

    override fun registerProtect(controller: XrayProtectController) {
        protectController = controller
        // DialerController is a pure interface (no native static init), so this
        // adapter is safe to construct on any JVM. We forward protect()'s boolean;
        // whether libXray honors a denial (fail-closed) is a B-3 device-smoke item.
        val adapter =
            object : libXray.DialerController {
                override fun protectFd(p0: Long): Boolean = controller.protect(p0.toInt())
            }
        ffi.registerDialer(adapter)
        ffi.registerListener(adapter)
    }

    override fun start(jsonConfig: String): Int {
        // Never log jsonConfig — it carries UUIDs / REALITY private keys.
        return try {
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
        // Idempotent on the libXray side; swallow any decode/native failure.
        runCatching { ffi.stopXray() }
    }

    override fun version(): String {
        val data = runCatching { decodeCallResponseData(ffi.xrayVersion()) }.getOrNull()
        return "Xray " + (data?.takeIf(String::isNotBlank) ?: "unknown")
    }

    override fun listenerReady(): Boolean = runCatching { ffi.getXrayState() }.getOrDefault(false)

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
        const val START_OK = 0
        const val START_REJECTED = 1
        const val MPH_CACHE_FILE_NAME = "geo.mph"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
