package com.poyka.ripdpi.core

import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.Socket
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
    /** Upstream API version 3 uses plain JSON for requests and responses. */
    fun invoke(requestJson: String): String

    @Throws(Exception::class)
    fun initDns(
        controller: libXray.DialerController,
        server: String,
    )

    fun resetDns()

    fun registerDialer(controller: libXray.DialerController)

    fun registerListener(controller: libXray.DialerController)
}

/** Keeps the Android-only gojni loader outside host-test code. */
internal class GomobileLibXrayFfi : LibXrayFfi {
    override fun invoke(requestJson: String): String = libXray.LibXray.invoke(requestJson)

    override fun initDns(
        controller: libXray.DialerController,
        server: String,
    ) {
        libXray.LibXray.setDNS(controller, server)
    }

    override fun resetDns() = libXray.LibXray.resetDNS()

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

    private val protectController = AtomicReference<XrayProtectController?>()
    private var dialerRegistered = false
    private var listenerRegistered = false
    private var inbound: XrayBridgeConfig? = null
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
            inbound = XrayBridgeConfig.parse(jsonConfig)
            // Relay endpoints are resolved by the service's eligible-underlay callback.
            // Any remaining Go system lookup is denied before socket I/O, without plaintext fallback.
            ffi.initDns(
                object : libXray.DialerController {
                    override fun protectFd(fd: Long): Boolean = false
                },
                "127.0.0.1:53",
            )
            val config = JSON.parseToJsonElement(jsonConfig).jsonObject.toMutableMap()
            val environment =
                when (val current = config["env"]) {
                    null, JsonNull -> mutableMapOf()
                    is JsonObject -> current.toMutableMap()
                    else -> error("Xray environment must be an object or null")
                }
            environment["xray.location.asset"] = JsonPrimitive(datDir)
            config["env"] = JsonObject(environment)
            val response = invoke("runXray", buildJsonObject { put("xrayJson", JsonObject(config).toString()) })
            if (response?.success == true) START_OK else START_REJECTED
        } catch (_: Exception) {
            // A native/API failure rejects activation without exposing configuration data.
            START_REJECTED
        }
    }

    override fun stop() {
        protectController.set(null)
        check(invoke("stopXray")?.success == true) { "Xray native cleanup failed" }
        ffi.resetDns()
        inbound = null
    }

    override fun version(): String {
        val data = runCatching { invoke("xrayVersion")?.takeIf(CallResponse::success)?.data as? JsonObject }.getOrNull()
        val version = (data?.get("version") as? JsonPrimitive)?.contentOrNull
        return "Xray " + (version?.takeIf(String::isNotBlank) ?: "unknown")
    }

    override fun listenerReady(): Boolean {
        val config = inbound ?: return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", config.inboundPort), ReadinessTimeoutMillis)
                socket.soTimeout = ReadinessTimeoutMillis
                // Ready means the inbound accepts this session's credentials, not merely that it is bound.
                LocalSocks5Client.authenticate(
                    socket.getInputStream(),
                    socket.getOutputStream(),
                    config.inboundCredentials,
                )
                true
            }
        }.getOrDefault(false)
    }

    override fun isAlive(): Boolean =
        runCatching {
            val data = invoke("getXrayState")?.takeIf(CallResponse::success)?.data as? JsonObject
            val running = data?.get("running") as? JsonPrimitive
            running?.takeUnless(JsonPrimitive::isString)?.booleanOrNull == true
        }.getOrDefault(false)

    @Serializable
    private data class CallResponse(
        val success: Boolean = false,
        val data: JsonElement = JsonNull,
        @SerialName("error") val error: String = "",
    )

    private fun invoke(
        method: String,
        payload: JsonObject = buildJsonObject {},
    ): CallResponse? {
        val request =
            buildJsonObject {
                put("apiVersion", 3)
                put("method", method)
                put("payload", payload)
            }
        val response = ffi.invoke(request.toString())
        return runCatching { JSON.decodeFromString(CallResponse.serializer(), response) }.getOrNull()
    }

    private companion object {
        const val ReadinessTimeoutMillis = 200
        const val START_OK = 0
        const val START_REJECTED = 1
        val JSON = RipDpiJson
    }
}
