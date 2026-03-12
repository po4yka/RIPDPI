package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.activeDnsSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TemporaryResolverOverride(
    val resolverId: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val tlsServerName: String,
    val bootstrapIps: List<String>,
    val dohUrl: String,
    val dnscryptProviderName: String,
    val dnscryptPublicKey: String,
    val reason: String,
    val appliedAt: Long,
) {
    fun toActiveDnsSettings(): ActiveDnsSettings =
        activeDnsSettings(
            dnsMode = DnsModeEncrypted,
            dnsProviderId = resolverId,
            dnsIp = bootstrapIps.firstOrNull().orEmpty(),
            dnsDohUrl = dohUrl,
            dnsDohBootstrapIps = bootstrapIps,
            encryptedDnsProtocol = protocol,
            encryptedDnsHost = host,
            encryptedDnsPort = port,
            encryptedDnsTlsServerName = tlsServerName,
            encryptedDnsBootstrapIps = bootstrapIps,
            encryptedDnsDohUrl = dohUrl,
            encryptedDnsDnscryptProviderName = dnscryptProviderName,
            encryptedDnsDnscryptPublicKey = dnscryptPublicKey,
        )

    fun matches(settings: ActiveDnsSettings): Boolean {
        val active = toActiveDnsSettings()
        return active == settings
    }
}

interface ResolverOverrideStore {
    val override: StateFlow<TemporaryResolverOverride?>

    fun setTemporaryOverride(override: TemporaryResolverOverride)

    fun clear()
}

@Singleton
class DefaultResolverOverrideStore
    @Inject
    constructor() : ResolverOverrideStore {
        private val state = MutableStateFlow<TemporaryResolverOverride?>(null)

        override val override: StateFlow<TemporaryResolverOverride?> = state.asStateFlow()

        override fun setTemporaryOverride(override: TemporaryResolverOverride) {
            state.value = override
        }

        override fun clear() {
            state.value = null
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ResolverOverrideStoreModule {
    @Binds
    @Singleton
    abstract fun bindResolverOverrideStore(
        store: DefaultResolverOverrideStore,
    ): ResolverOverrideStore
}
