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

internal class ParcelFileDescriptorVpnTunnelSession(
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
        host: VpnTunnelBuilderHost,
        dns: String,
        ipv6: Boolean,
    ): VpnTunnelSession
}

@Singleton
class DefaultVpnTunnelSessionProvider
    @Inject
    constructor() : VpnTunnelSessionProvider {
        override fun establish(
            host: VpnTunnelBuilderHost,
            dns: String,
            ipv6: Boolean,
        ): VpnTunnelSession {
            val descriptor =
                host.createTunnelBuilder(dns, ipv6).establish()
                    ?: throw IllegalStateException("VPN connection failed")
            return descriptor
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class VpnTunnelSessionProviderModule {
    @Binds
    @Singleton
    abstract fun bindVpnTunnelSessionProvider(provider: DefaultVpnTunnelSessionProvider): VpnTunnelSessionProvider
}
