package com.poyka.ripdpi.core

import javax.inject.Inject

/** Ephemeral control-plane result. Never persist, serialize or publish this identity. */
class TunKernelIdentity(
    val interfaceName: String,
    val interfaceIndex: Int,
) {
    override fun toString(): String = "TunKernelIdentity(redacted)"
}

/** Borrows the caller's live TUN descriptor for a synchronous kernel identity read. */
class TunKernelIdentityNativeBindings
    @Inject
    constructor() {
        fun readTunKernelIdentity(tunFd: Int): TunKernelIdentity? {
            Tun2SocksNativeLoader.ensureLoaded()
            return jniReadTunKernelIdentity(tunFd)
        }

        private external fun jniReadTunKernelIdentity(tunFd: Int): TunKernelIdentity?
    }
