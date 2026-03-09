package com.poyka.ripdpi.core

object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic
    external fun TProxyStartService(
        configPath: String,
        fd: Int,
    )

    @JvmStatic
    external fun TProxyStopService()

    @JvmStatic
    @Suppress("unused") // JNI binding reserved for future stats API
    external fun TProxyGetStats(): LongArray
}
