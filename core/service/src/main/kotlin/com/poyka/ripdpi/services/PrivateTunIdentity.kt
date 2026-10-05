package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.TunKernelIdentityNativeBindings
import java.net.NetworkInterface
import java.net.SocketException
import javax.inject.Inject

/** Process-only correlation material; no public evidence or telemetry may contain it. */
internal class PrivateTunIdentity private constructor(
    private val interfaceName: String,
    private val interfaceIndex: Int,
) {
    fun matches(other: PrivateTunIdentity): Boolean =
        interfaceName == other.interfaceName && interfaceIndex == other.interfaceIndex

    override fun toString(): String = "PrivateTunIdentity(redacted)"

    companion object {
        fun create(
            interfaceName: String?,
            interfaceIndex: Int,
        ): PrivateTunIdentity? =
            interfaceName
                ?.takeIf { it.isNotEmpty() && '\u0000' !in it && interfaceIndex > 0 }
                ?.let { PrivateTunIdentity(it, interfaceIndex) }

        fun fromLinkInterface(interfaceName: String?): PrivateTunIdentity? =
            try {
                interfaceName?.let { name -> create(name, NetworkInterface.getByName(name)?.index ?: 0) }
            } catch (_: SocketException) {
                null
            }
    }
}

internal class TunKernelIdentityReader
    @Inject
    constructor(
        private val bindings: TunKernelIdentityNativeBindings,
    ) {
        fun read(tunFd: Int): PrivateTunIdentity? =
            try {
                bindings.readTunKernelIdentity(tunFd)?.let { identity ->
                    PrivateTunIdentity.create(identity.interfaceName, identity.interfaceIndex)
                }
            } catch (_: RuntimeException) {
                null
            } catch (_: LinkageError) {
                null
            }
    }
