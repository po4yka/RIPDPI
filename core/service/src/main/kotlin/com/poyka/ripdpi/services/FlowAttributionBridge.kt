package com.poyka.ripdpi.services

import androidx.annotation.Keep
import javax.inject.Inject

/**
 * The Kotlin object the tun2socks native worker calls over JNI (`noteFlow`) to
 * report a freshly seen flow's 5-tuple. It delegates straight to
 * [FlowAppAttributionStore], which resolves the owning app off the hot path and
 * owns the in-memory attribution map.
 *
 * `@Keep` (on the class and the method) is load-bearing: `noteFlow` is invoked
 * only via JNI, so without it R8 would rename or strip the method in release
 * builds and the native `call_method` lookup would fail. The JNI signature the
 * native side resolves is `(ILjava/lang/String;ILjava/lang/String;I)V`.
 */
@Keep
class FlowAttributionBridge
    @Inject
    constructor(
        private val store: FlowAppAttributionStore,
    ) {
        @Keep
        fun noteFlow(
            protocol: Int,
            localIp: String,
            localPort: Int,
            remoteIp: String,
            remotePort: Int,
        ) {
            store.noteFlow(protocol, localIp, localPort, remoteIp, remotePort)
        }
    }
