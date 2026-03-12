package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.core.RipDpiProxyCmdPreferences
import com.poyka.ripdpi.core.RipDpiProxyJsonPreferences
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.resolveHostAutolearnStorePath
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.VpnDnsPolicyJson
import com.poyka.ripdpi.data.toVpnDnsPolicyJson
import com.poyka.ripdpi.data.toActiveDnsSettings
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.toPolicyJson
import com.poyka.ripdpi.proto.AppSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

data class ConnectionPolicyResolution(
    val settings: AppSettings,
    val proxyPreferences: RipDpiProxyPreferences,
    val activeDns: ActiveDnsSettings,
    val vpnDnsOverride: VpnDnsPolicyJson? = null,
    val matchedNetworkPolicy: RememberedNetworkPolicyEntity? = null,
    val appliedPolicy: RememberedNetworkPolicyJson? = null,
    val networkScopeKey: String? = null,
    val resolverFallbackReason: String? = null,
)

interface ConnectionPolicyResolver {
    suspend fun resolve(
        mode: Mode,
        resolverOverride: TemporaryResolverOverride? = null,
    ): ConnectionPolicyResolution
}

@Singleton
class DefaultConnectionPolicyResolver
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
    ) : ConnectionPolicyResolver {
        override suspend fun resolve(
            mode: Mode,
            resolverOverride: TemporaryResolverOverride?,
        ): ConnectionPolicyResolution {
            val settings = appSettingsRepository.snapshot()
            val dnsResolution = resolveEffectiveDns(settings, resolverOverride)
            val fingerprint = networkFingerprintProvider.capture()
            val networkScopeKey = fingerprint?.scopeKey()
            val hostAutolearnStorePath =
                settings
                    .takeIf { it.hostAutolearnEnabled }
                    ?.let { resolveHostAutolearnStorePath(context) }

            val baselinePreferences =
                if (settings.enableCmdSettings) {
                    RipDpiProxyCmdPreferences(settings.cmdArgs)
                } else {
                    RipDpiProxyUIPreferences(
                        settings = settings,
                        hostAutolearnStorePath = hostAutolearnStorePath,
                        networkScopeKey = networkScopeKey,
                    )
                }

            val baselinePolicy =
                if (!settings.enableCmdSettings && settings.networkStrategyMemoryEnabled && fingerprint != null && networkScopeKey != null) {
                    RememberedNetworkPolicyJson(
                        fingerprintHash = networkScopeKey,
                        mode = mode.preferenceValue,
                        summary = fingerprint.summary(),
                        proxyConfigJson = baselinePreferences.toNativeConfigJson(),
                        vpnDnsPolicy =
                            if (mode == Mode.VPN) {
                                dnsResolution.activeDns.toVpnDnsPolicyJson()
                            } else {
                                null
                            },
                    )
                } else {
                    null
                }

            if (settings.enableCmdSettings || !settings.networkStrategyMemoryEnabled || networkScopeKey == null) {
                return ConnectionPolicyResolution(
                    settings = settings,
                    proxyPreferences = baselinePreferences,
                    activeDns = dnsResolution.activeDns,
                    vpnDnsOverride = null,
                    matchedNetworkPolicy = null,
                    appliedPolicy = baselinePolicy,
                    networkScopeKey = networkScopeKey,
                    resolverFallbackReason = dnsResolution.override?.reason,
                )
            }

            val matchedPolicy =
                rememberedNetworkPolicyStore.findValidatedMatch(
                    fingerprintHash = networkScopeKey,
                    mode = mode,
                )
                    ?: return ConnectionPolicyResolution(
                        settings = settings,
                        proxyPreferences = baselinePreferences,
                        activeDns = dnsResolution.activeDns,
                        vpnDnsOverride = null,
                        matchedNetworkPolicy = null,
                        appliedPolicy = baselinePolicy,
                        networkScopeKey = networkScopeKey,
                        resolverFallbackReason = dnsResolution.override?.reason,
                    )

            val rememberedPolicy = matchedPolicy.toPolicyJson()
            val proxyPreferences =
                RipDpiProxyJsonPreferences(
                    configJson = matchedPolicy.proxyConfigJson,
                    hostAutolearnStorePath = hostAutolearnStorePath,
                    networkScopeKey = networkScopeKey,
                )
            val vpnDnsOverride = if (mode == Mode.VPN) rememberedPolicy?.vpnDnsPolicy else null
            return ConnectionPolicyResolution(
                settings = settings,
                proxyPreferences = proxyPreferences,
                activeDns = vpnDnsOverride?.toActiveDnsSettings() ?: dnsResolution.activeDns,
                vpnDnsOverride = vpnDnsOverride,
                matchedNetworkPolicy = matchedPolicy,
                appliedPolicy = rememberedPolicy,
                networkScopeKey = networkScopeKey,
                resolverFallbackReason =
                    if (vpnDnsOverride == null) {
                        dnsResolution.override?.reason
                    } else {
                        null
                    },
            )
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectionPolicyResolverModule {
    @Binds
    @Singleton
    abstract fun bindConnectionPolicyResolver(
        resolver: DefaultConnectionPolicyResolver,
    ): ConnectionPolicyResolver
}
