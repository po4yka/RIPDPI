package com.poyka.ripdpi.core

/**
 * Offline stub for [XrayNativeBridge] when the libXray AAR is NOT linked.
 *
 * Compiled only in the `xrayStub` source set (the default offline / native-less
 * build). It references NO `libXray.*` symbols so it compiles with no AAR on the
 * classpath. Every method throws [NotImplementedError], matching the observable
 * behavior of the pre-link skeleton; the real gomobile-backed implementation
 * lives in the `xrayLinked` source set under the same FQN.
 *
 * Exactly one of `xrayLinked` / `xrayStub` is ever added to the main source set
 * (see `core/engine/build.gradle.kts`), so this FQN is defined once.
 *
 * @param datDir absolute path to the geoip/geosite `.dat` asset directory; kept
 *   in the signature for parity with the linked constructor.
 */
class XrayNativeBridgeLibXrayImpl(
    @Suppress("unused") private val datDir: String,
) : XrayNativeBridge {
    override fun registerProtect(controller: XrayProtectController): Unit = throwUnlinked("registerProtect")

    override fun start(jsonConfig: String): Int = throwUnlinked("start")

    override fun stop(): Unit = throwUnlinked("stop")

    override fun version(): String = throwUnlinked("version")

    override fun listenerReady(): Boolean = throwUnlinked("listenerReady")

    override fun isAlive(): Boolean = throwUnlinked("isAlive")

    private fun throwUnlinked(method: String): Nothing =
        throw NotImplementedError(
            "XrayNativeBridgeLibXrayImpl.$method requires the gomobile libXray AAR; " +
                "build with the artifact present or -Pripdpi.linkXray=true (UNVERIFIED IN CI).",
        )
}
