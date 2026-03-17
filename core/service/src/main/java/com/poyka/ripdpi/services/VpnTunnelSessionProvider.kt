package com.poyka.ripdpi.services

import android.os.ParcelFileDescriptor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface VpnTunnelSession {
    val tunFd: Int

    fun close()
}

private class ParcelFileDescriptorVpnTunnelSession(
    private val descriptor: ParcelFileDescriptor,
) : VpnTunnelSession {
    override val tunFd: Int
        get() = descriptor.fd

    override fun close() {
        descriptor.close()
    }
}

interface VpnTunnelSessionProvider {
    fun establish(
        service: RipDpiVpnService,
        dns: String,
        ipv6: Boolean,
    ): VpnTunnelSession
}

@Singleton
class DefaultVpnTunnelSessionProvider
    @Inject
    constructor() : VpnTunnelSessionProvider {
        override fun establish(
            service: RipDpiVpnService,
            dns: String,
            ipv6: Boolean,
        ): VpnTunnelSession {
            val descriptor =
                service.createBuilder(dns, ipv6).establish()
                    ?: throw IllegalStateException("VPN connection failed")
            return ParcelFileDescriptorVpnTunnelSession(descriptor)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class VpnTunnelSessionProviderModule {
    @Binds
    @Singleton
    abstract fun bindVpnTunnelSessionProvider(provider: DefaultVpnTunnelSessionProvider): VpnTunnelSessionProvider
}
