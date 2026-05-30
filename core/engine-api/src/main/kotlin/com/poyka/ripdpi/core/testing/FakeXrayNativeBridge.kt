package com.poyka.ripdpi.core.testing

import com.poyka.ripdpi.core.XrayNativeBridge
import com.poyka.ripdpi.core.XrayProtectController

/**
 * Deterministic in-memory [XrayNativeBridge] for unit and service tests.
 *
 * Lets a test drive every observable libXray behaviour without gomobile:
 * - [startCode] — the status code returned from [start].
 * - [readyAfterPolls] — how many [listenerReady] polls precede a `true`
 *   (simulates a slow listener); `0` means ready immediately.
 * - [aliveDuringStartup] — set to `false` to simulate a process that exited
 *   before the listener came up (crash on startup).
 * - [versionString] — the version reported by [version].
 * - [stopBehavior] — whether [stop] returns cleanly, throws, or hangs.
 *
 * Records [registeredProtectController] and the order of calls so tests can
 * assert the protect-first invariant (protect registered before start).
 */
open class FakeXrayNativeBridge(
    var startCode: Int = 0,
    var readyAfterPolls: Int = 0,
    var aliveDuringStartup: Boolean = true,
    var versionString: String = "Xray 26.1.18 (fake)",
    var stopBehavior: StopBehavior = StopBehavior.Clean,
) : XrayNativeBridge {
    /** Behaviour of the fake's [stop]. */
    enum class StopBehavior {
        Clean,
        Throw,
        Hang,
    }

    /** The protect controller registered via [registerProtect], or null. */
    var registeredProtectController: XrayProtectController? = null
        private set

    /** Ordered log of bridge method names, for invariant assertions. */
    val callLog: MutableList<String> = mutableListOf()

    var startedConfig: String? = null
        private set

    var startCount: Int = 0
        private set

    var stopCount: Int = 0
        private set

    private var listenerPollCount: Int = 0
    private var running: Boolean = false

    override fun registerProtect(controller: XrayProtectController) {
        callLog += "registerProtect"
        registeredProtectController = controller
    }

    override fun start(jsonConfig: String): Int {
        callLog += "start"
        startCount++
        startedConfig = jsonConfig
        if (startCode == 0) {
            running = true
            listenerPollCount = 0
        }
        return startCode
    }

    override fun stop() {
        callLog += "stop"
        stopCount++
        running = false
        when (stopBehavior) {
            StopBehavior.Clean -> {
                Unit
            }

            StopBehavior.Throw -> {
                error("fake libXray stop failure")
            }

            StopBehavior.Hang -> {
                // Busy-block long enough that a bounded stop times out under test.
                Thread.sleep(HangMillis)
            }
        }
    }

    override fun version(): String {
        callLog += "version"
        return versionString
    }

    override fun listenerReady(): Boolean {
        callLog += "listenerReady"
        if (!running) return false
        val ready = listenerPollCount >= readyAfterPolls
        listenerPollCount++
        return ready
    }

    override fun isAlive(): Boolean = running && aliveDuringStartup

    private companion object {
        const val HangMillis = 10_000L
    }
}
