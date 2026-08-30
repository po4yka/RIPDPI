package com.poyka.ripdpi.services

import android.net.VpnService
import com.poyka.ripdpi.service.runtime.proxy.ProxyServiceRuntimeCoordinator
import com.poyka.ripdpi.service.runtime.vpn.VpnServiceRuntimeCoordinator
import dagger.BindsInstance
import dagger.hilt.DefineComponent
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Scope
import javax.inject.Singleton
import kotlin.annotation.AnnotationRetention.RUNTIME

@Scope
@Retention(RUNTIME)
internal annotation class ServiceSessionScope

@ServiceSessionScope
@DefineComponent(parent = SingletonComponent::class)
internal interface ProxyServiceSessionComponent

@DefineComponent.Builder
internal interface ProxyServiceSessionComponentBuilder {
    fun host(
        @BindsInstance host: ServiceCoordinatorHost,
    ): ProxyServiceSessionComponentBuilder

    fun build(): ProxyServiceSessionComponent
}

@EntryPoint
@InstallIn(ProxyServiceSessionComponent::class)
internal interface ProxyServiceSessionEntryPoint {
    fun stateInitializer(): ServiceSessionStateInitializer

    fun coordinator(): ProxyServiceRuntimeCoordinator
}

@ServiceSessionScope
@DefineComponent(parent = SingletonComponent::class)
internal interface VpnServiceSessionComponent

@DefineComponent.Builder
internal interface VpnServiceSessionComponentBuilder {
    fun host(
        @BindsInstance host: VpnCoordinatorHost,
    ): VpnServiceSessionComponentBuilder

    fun vpnService(
        @BindsInstance vpnService: VpnService,
    ): VpnServiceSessionComponentBuilder

    fun build(): VpnServiceSessionComponent
}

@EntryPoint
@InstallIn(VpnServiceSessionComponent::class)
internal interface VpnServiceSessionEntryPoint {
    fun stateInitializer(): ServiceSessionStateInitializer

    fun coordinator(): VpnServiceRuntimeCoordinator

    fun protectSocketServer(): VpnProtectSocketServer
}

@Scope
@Retention(RUNTIME)
internal annotation class BootstrapProxySessionScope

@BootstrapProxySessionScope
@DefineComponent(parent = SingletonComponent::class)
internal interface BootstrapProxySessionComponent

@DefineComponent.Builder
internal interface BootstrapProxySessionComponentBuilder {
    fun sessionScope(
        @BindsInstance sessionScope: CoroutineScope,
    ): BootstrapProxySessionComponentBuilder

    fun build(): BootstrapProxySessionComponent
}

@EntryPoint
@InstallIn(BootstrapProxySessionComponent::class)
internal interface BootstrapProxySessionEntryPoint {
    fun proxyRuntimeSupervisor(): ProxyRuntimeSupervisor
}

internal interface BootstrapProxyRuntimeSupervisorSessionFactory {
    fun create(scope: CoroutineScope): ProxyRuntimeSupervisor
}

@Singleton
internal class DefaultBootstrapProxyRuntimeSupervisorSessionFactory
    @Inject
    constructor(
        private val componentBuilderProvider: Provider<BootstrapProxySessionComponentBuilder>,
    ) : BootstrapProxyRuntimeSupervisorSessionFactory {
        override fun create(scope: CoroutineScope): ProxyRuntimeSupervisor {
            val component = componentBuilderProvider.get().sessionScope(scope).build()
            return EntryPoints.get(component, BootstrapProxySessionEntryPoint::class.java).proxyRuntimeSupervisor()
        }
    }
