package com.poyka.ripdpi.core

interface XrayNativeBridge {
    fun registerProtect(controller: XrayProtectController)

    fun start(jsonConfig: String): Int

    fun stop()

    fun version(): String

    fun listenerReady(): Boolean

    fun isAlive(): Boolean = true
}

fun interface XrayProtectController {
    fun protect(fd: Int): Boolean
}
