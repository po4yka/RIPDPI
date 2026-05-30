// UNVERIFIED IN CI: requires gomobile libXray
//
// This file is the real [XrayNativeBridge] skeleton over the gomobile-built
// libXray AAR (`libxray`/`xray-core` pins in gradle/libs.versions.toml). The
// AAR is imported only by the native-linking module via the
// `ripdpi.prebuiltXrayAarDir` Gradle input — it is NOT on the offline-CI
// classpath of `:core:engine-api`, and gomobile is absent from this CI image.
//
// Therefore the gomobile entry points are referenced by name in comments and
// the methods throw [NotImplementedError] until this class is moved/compiled in
// the module that links the libXray AAR. The lifecycle/state/telemetry logic in
// [RipDpiXrayRuntime] is fully verified against [FakeXrayNativeBridge]; this
// skeleton only documents the native binding contract.
//
// gomobile entry points (package `libXray`, Go-bound to a Kotlin facade class):
//   LibXray.runXrayFromJSON(datDir: String, configJson: String): String  -> JSON {success,err}
//   LibXray.stopXray(): String                                           -> JSON {success,err}
//   LibXray.xrayVersion(): String                                        -> e.g. "26.4.7"
//   LibXray.registerProtect(p: ProtectSet)                               -> fd protect callback
// See docs/native/libxray-packaging.md.

package com.poyka.ripdpi.core

/**
 * Real [XrayNativeBridge] backed by the gomobile libXray AAR.
 *
 * UNVERIFIED IN CI — see the file header. The body throws until this class is
 * compiled in the libXray-linking module. The intended wiring is documented
 * inline so the move is mechanical.
 *
 * @param datDir absolute path to the geoip/geosite `.dat` asset directory libXray
 *   reads at start (never contains secrets).
 */
class XrayNativeBridgeLibXrayImpl(
    @Suppress("unused") private val datDir: String,
) : XrayNativeBridge {
    @Volatile
    private var protectController: XrayProtectController? = null

    override fun registerProtect(controller: XrayProtectController) {
        protectController = controller
        // Real: install a gomobile `ProtectSet` whose `Protect(fd: Long): Boolean`
        // delegates to `controller.protect(fd.toInt())`, then
        // `LibXray.registerProtect(protectSet)`. MUST run before runXrayFromJSON
        // so the first outbound socket Xray opens is protected — see
        // .claude/rules/vpnservice-protect-invariant.md.
        throwUnlinked("registerProtect")
    }

    override fun start(jsonConfig: String): Int {
        // Real: val result = LibXray.runXrayFromJSON(datDir, jsonConfig)
        //       return if (parseSuccess(result)) 0 else NON_ZERO
        // Do NOT log jsonConfig — it carries UUIDs / REALITY private keys.
        throwUnlinked("start")
    }

    override fun stop() {
        // Real: LibXray.stopXray()  (idempotent on the libXray side)
        throwUnlinked("stop")
    }

    override fun version(): String {
        // Real: "Xray " + LibXray.xrayVersion()
        throwUnlinked("version")
    }

    override fun listenerReady(): Boolean {
        // Real: probe the local inbound (127.0.0.1:<localInboundPort>) with a
        // bounded loopback connect, or read libXray's listener-ready status JSON.
        throwUnlinked("listenerReady")
    }

    override fun isAlive(): Boolean {
        // Real: read libXray run-state; false once the core has exited.
        throwUnlinked("isAlive")
    }

    private fun throwUnlinked(method: String): Nothing =
        throw NotImplementedError(
            "XrayNativeBridgeLibXrayImpl.$method requires the gomobile libXray AAR; " +
                "compile this class in the libXray-linking module (UNVERIFIED IN CI).",
        )
}
