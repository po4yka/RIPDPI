package com.poyka.ripdpi.core.testing

import com.poyka.ripdpi.core.XrayNativeBridge
import com.poyka.ripdpi.core.XrayProtectController

open class FakeXrayNativeBridge(
    var startCode: Int = 0,
    var readyAfterPolls: Int = 0,
    var aliveDuringStartup: Boolean = true,
    var versionString: String = "Xray 26.1.18 (fake)",
    var stopBehavior: StopBehavior = StopBehavior.Clean,
) : XrayNativeBridge {
    enum class StopBehavior {
        Clean,
        Throw,
        Hang,
    }

    var registeredProtectController: XrayProtectController? = null
        private set

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
            }

            StopBehavior.Throw -> {
                error("fake libXray stop failure")
            }

            StopBehavior.Hang -> {
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
