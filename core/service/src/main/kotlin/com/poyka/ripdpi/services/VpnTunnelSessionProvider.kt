package com.poyka.ripdpi.services

import android.os.ParcelFileDescriptor
import com.poyka.ripdpi.proto.AppSettings
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
    suspend fun establish(
        host: VpnTunnelBuilderHost,
        dns: String,
        ipv6: Boolean,
        appRoutingPlan: VpnAppRoutingPlan,
        interfaceSettings: AppSettings,
        httpProxyPort: Int? = null,
        networkParameters: VpnTunnelNetworkParameters = host.currentTunnelNetworkParameters(),
        profileInterface: VpnProfileInterface? = null,
    ): VpnTunnelSession
}

@Singleton
class DefaultVpnTunnelSessionProvider
    @Inject
    constructor() : VpnTunnelSessionProvider {
        override suspend fun establish(
            host: VpnTunnelBuilderHost,
            dns: String,
            ipv6: Boolean,
            appRoutingPlan: VpnAppRoutingPlan,
            interfaceSettings: AppSettings,
            httpProxyPort: Int?,
            networkParameters: VpnTunnelNetworkParameters,
            profileInterface: VpnProfileInterface?,
        ): VpnTunnelSession {
            val descriptor =
                host
                    .createTunnelBuilder(
                        dns,
                        ipv6,
                        appRoutingPlan,
                        interfaceSettings,
                        httpProxyPort,
                        networkParameters,
                        profileInterface,
                    ).establish()
                    ?: error("VPN connection failed")
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
