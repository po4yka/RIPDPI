package com.poyka.ripdpi.core

/**
 * Seam over the libXray Go-bridge (`gomobile` AAR).
 *
 * [RipDpiXrayRuntime] depends ONLY on this interface so its lifecycle and
 * telemetry logic is fully unit-testable with a fake, without dragging in the
 * gomobile native artifact. The real implementation lives in `:core:engine`
 * (`XrayNativeBridgeLibXrayImpl`, gated source set `xrayLinked`); its logic is
 * verified through an FFI seam and actual Android loopback tests.
 * Every blocking native operation must run on the process-owned native worker.
 *
 * ### Protect-first contract
 * [registerProtect] MUST be wired BEFORE [start] is invoked. Xray-core opens
 * outbound sockets the instant it starts; if the protect callback is not
 * registered first, the very first outbound socket is captured by the VPN's own
 * TUN route and the packet loop fires. See
 * `.claude/rules/vpnservice-protect-invariant.md`.
 */
interface XrayNativeBridge {
    /**
     * Register the protect controller that libXray invokes for every outbound
     * socket it opens. MUST be called before [start]. Idempotent: registering a
     * second controller replaces the first.
     */
    fun registerProtect(controller: XrayProtectController)

    /**
     * Start Xray-core from a fully-rendered JSON config.
     *
     * @return libXray status code: `0` on a clean start request, non-zero on
     *   immediate rejection (bad config, port in use, etc.). A `0` here only
     *   means the start was *accepted*; readiness is observed separately via
     *   [listenerReady].
     */
    fun start(jsonConfig: String): Int

    /** Stop Xray-core. May block; throws unless native cleanup is confirmed. */
    fun stop()

    /** libXray / xray-core version string, e.g. `"Xray 26.1.18"`. */
    fun version(): String

    /**
     * Whether the configured local inbound listener is accepting connections.
     * Polled by [RipDpiXrayRuntime.awaitReady] until it returns `true` or the
     * bounded timeout elapses.
     */
    fun listenerReady(): Boolean

    /**
     * Whether the Xray process is still running. Distinguishes a slow-to-listen
     * start from a process that crashed / exited during startup so
     * [RipDpiXrayRuntime.awaitReady] can fail fast with a typed crash cause
     * rather than waiting out the full readiness timeout. Defaults to `true`
     * for bridges that cannot observe liveness.
     */
    fun isAlive(): Boolean = true
}

/**
 * Protect seam invoked by libXray for every outbound socket it opens.
 *
 * The production controller delegates to `android.net.VpnService.protect(int)`
 * via the existing `:core:service` protect path; tests inject a recording fake.
 * Returning `false` MUST cause libXray to close the socket and fail the
 * connection rather than proceed unprotected.
 */
fun interface XrayProtectController {
    /** Protect [fd]; return `true` on success, `false` to fail the socket. */
    fun protect(fd: Int): Boolean
}
