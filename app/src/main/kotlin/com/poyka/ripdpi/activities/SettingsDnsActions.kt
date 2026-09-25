package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.EncryptedDnsProtocolOdoh
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.dnsProviderById
import com.poyka.ripdpi.data.normalizeDnsBootstrapIps
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceIntentArbiter
import com.poyka.ripdpi.services.ServiceStartResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private const val defaultDnsPort = 443

internal class SettingsDnsActions(
    private val mutations: SettingsMutationRunner,
    private val serviceStateStore: ServiceStateStore,
    private val serviceController: ServiceController,
    private val serviceIntentArbiter: ServiceIntentArbiter,
) {
    fun selectBuiltInDnsProvider(providerId: String) {
        val resolver = dnsProviderById(providerId) ?: return
        updateDnsSetting(
            key = "dnsProviderId",
            value = providerId,
        ) {
            setDnsMode(DnsModeEncrypted)
            setDnsProviderId(resolver.providerId)
            setDnsIp(resolver.primaryIp)
            setEncryptedDnsProtocol(resolver.protocol)
            setEncryptedDnsHost(resolver.host)
            setEncryptedDnsPort(resolver.port)
            setEncryptedDnsTlsServerName(resolver.tlsServerName)
            clearEncryptedDnsBootstrapIps()
            addAllEncryptedDnsBootstrapIps(resolver.bootstrapIps)
            setEncryptedDnsDohUrl(resolver.dohUrl.orEmpty())
            setEncryptedDnsDnscryptProviderName(resolver.dnscryptProviderName.orEmpty())
            setEncryptedDnsDnscryptPublicKey(resolver.dnscryptPublicKey.orEmpty())
        }
    }

    fun setPlainDnsServer(dnsIp: String) {
        updateDnsSetting(
            key = "dnsIp",
            value = dnsIp,
        ) {
            setDnsMode(DnsModePlainUdp)
            setDnsProviderId(DnsProviderCustom)
            setDnsIp(dnsIp)
            setEncryptedDnsProtocol("")
            setEncryptedDnsHost("")
            setEncryptedDnsPort(0)
            setEncryptedDnsTlsServerName("")
            clearEncryptedDnsBootstrapIps()
            setEncryptedDnsDohUrl("")
            setEncryptedDnsDnscryptProviderName("")
            setEncryptedDnsDnscryptPublicKey("")
        }
    }

    fun setCustomDohResolver(
        dohUrl: String,
        bootstrapIps: List<String>,
    ) {
        val normalizedBootstrapIps = normalizeDnsBootstrapIps(bootstrapIps)
        updateDnsSetting(
            key = "encryptedDnsDohUrl",
            value = dohUrl,
        ) {
            val host =
                runCatching {
                    java.net
                        .URI(dohUrl.trim())
                        .host
                        .orEmpty()
                }.getOrDefault("")
            val port =
                runCatching {
                    val uri = java.net.URI(dohUrl.trim())
                    if (uri.port > 0) uri.port else defaultDnsPort
                }.getOrDefault(defaultDnsPort)
            setDnsMode(DnsModeEncrypted)
            setDnsProviderId(DnsProviderCustom)
            setDnsIp(normalizedBootstrapIps.firstOrNull().orEmpty())
            setEncryptedDnsProtocol(EncryptedDnsProtocolDoh)
            setEncryptedDnsHost(host)
            setEncryptedDnsPort(port)
            setEncryptedDnsTlsServerName(host)
            clearEncryptedDnsBootstrapIps()
            addAllEncryptedDnsBootstrapIps(normalizedBootstrapIps)
            setEncryptedDnsDohUrl(dohUrl.trim())
            setEncryptedDnsDnscryptProviderName("")
            setEncryptedDnsDnscryptPublicKey("")
        }
    }

    fun setCustomDotResolver(
        protocol: String,
        selectedMode: Mode,
        host: String,
        port: Int,
        tlsServerName: String,
        bootstrapIps: List<String>,
    ) {
        require(protocol == EncryptedDnsProtocolDot || protocol == EncryptedDnsProtocolDoq)
        if (protocol == EncryptedDnsProtocolDoq && !canSaveDoq(selectedMode)) return
        val normalizedBootstrapIps = normalizeDnsBootstrapIps(bootstrapIps)
        updateDnsSetting(
            key = "encryptedDnsHost",
            value = host,
            canApply = { protocol != EncryptedDnsProtocolDoq || canSaveDoq(selectedMode) },
            requiresVpnStartReservation = protocol == EncryptedDnsProtocolDoq,
            canApplyInTransaction = {
                protocol != EncryptedDnsProtocolDoq ||
                    (ripdpiMode == Mode.Proxy.preferenceValue && canSaveDoq(selectedMode))
            },
        ) {
            setDnsMode(DnsModeEncrypted)
            setDnsProviderId(DnsProviderCustom)
            setDnsIp(normalizedBootstrapIps.firstOrNull().orEmpty())
            setEncryptedDnsProtocol(protocol)
            setEncryptedDnsHost(host.trim())
            setEncryptedDnsPort(port)
            setEncryptedDnsTlsServerName(tlsServerName.trim())
            clearEncryptedDnsBootstrapIps()
            addAllEncryptedDnsBootstrapIps(normalizedBootstrapIps)
            setEncryptedDnsDohUrl("")
            setEncryptedDnsDnscryptProviderName("")
            setEncryptedDnsDnscryptPublicKey("")
        }
    }

    fun setCustomDnsCryptResolver(
        host: String,
        port: Int,
        providerName: String,
        publicKey: String,
        bootstrapIps: List<String>,
    ) {
        val normalizedBootstrapIps = normalizeDnsBootstrapIps(bootstrapIps)
        updateDnsSetting(
            key = "encryptedDnsDnscryptProviderName",
            value = providerName,
        ) {
            setDnsMode(DnsModeEncrypted)
            setDnsProviderId(DnsProviderCustom)
            setDnsIp(normalizedBootstrapIps.firstOrNull().orEmpty())
            setEncryptedDnsProtocol(EncryptedDnsProtocolDnsCrypt)
            setEncryptedDnsHost(host.trim())
            setEncryptedDnsPort(port)
            setEncryptedDnsTlsServerName("")
            clearEncryptedDnsBootstrapIps()
            addAllEncryptedDnsBootstrapIps(normalizedBootstrapIps)
            setEncryptedDnsDohUrl("")
            setEncryptedDnsDnscryptProviderName(providerName.trim())
            setEncryptedDnsDnscryptPublicKey(publicKey.trim())
        }
    }

    fun setCustomOdohResolver(
        fields: OdohResolverFields,
        bootstrapIps: List<String>,
    ) {
        require(fields.isValid())
        val url = java.net.URI(fields.proxyUrl.trim())
        val normalizedBootstrapIps = normalizeDnsBootstrapIps(bootstrapIps)
        updateDnsSetting(
            key = "encryptedDnsOdohProxyUrl",
            value = fields.proxyUrl,
        ) {
            setDnsMode(DnsModeEncrypted)
            setDnsProviderId(DnsProviderCustom)
            setDnsIp(normalizedBootstrapIps.firstOrNull().orEmpty())
            setEncryptedDnsProtocol(EncryptedDnsProtocolOdoh)
            setEncryptedDnsHost(url.host)
            setEncryptedDnsPort(url.port.takeIf { it > 0 } ?: defaultDnsPort)
            setEncryptedDnsTlsServerName(url.host)
            clearEncryptedDnsBootstrapIps()
            addAllEncryptedDnsBootstrapIps(normalizedBootstrapIps)
            setEncryptedDnsDohUrl("")
            setEncryptedDnsDnscryptProviderName("")
            setEncryptedDnsDnscryptPublicKey("")
            setEncryptedDnsOdohProxyUrl(fields.proxyUrl.trim())
            setEncryptedDnsOdohProxyOperatorId(fields.proxyOperatorId.trim())
            setEncryptedDnsOdohTargetHost(fields.targetHost.trim())
            setEncryptedDnsOdohTargetPath(fields.targetPath.trim())
            setEncryptedDnsOdohTargetOperatorId(fields.targetOperatorId.trim())
            setEncryptedDnsOdohConfigSource(fields.configSource)
            setEncryptedDnsOdohConfigsHex(fields.configsHex.trim())
            setEncryptedDnsOdohConfigsRetrievedAtSecs(fields.configsRetrievedAtSecs)
            setEncryptedDnsOdohConfigsTtlSecs(fields.configsTtlSecs)
        }
    }

    fun setIpv6Enabled(enabled: Boolean) {
        updateDnsSetting(
            key = "ipv6Enable",
            value = enabled.toString(),
        ) {
            setIpv6Enable(enabled)
        }
    }

    private fun updateDnsSetting(
        key: String,
        value: String,
        canApply: () -> Boolean = { true },
        canApplyInTransaction: com.poyka.ripdpi.proto.AppSettings.Builder.() -> Boolean = { true },
        requiresVpnStartReservation: Boolean = false,
        transform: SettingsMutation,
    ) {
        mutations.launch {
            if (!canApply()) return@launch
            val lease =
                if (requiresVpnStartReservation) {
                    serviceIntentArbiter.tryReserveDoqSave(canApply)
                } else {
                    null
                }
            if (requiresVpnStartReservation && lease == null) return@launch
            val applied =
                try {
                    updateSettingAndAwait(
                        key = key,
                        value = value,
                        canApply = canApplyInTransaction,
                        transform = transform,
                    )
                } finally {
                    lease?.close()
                }
            if (!applied) return@launch
            if (canApply()) applySavedDnsToRunningService()
        }
    }

    private fun canSaveDoq(selectedMode: Mode): Boolean {
        val (status, activeMode) = serviceStateStore.status.value
        return selectedMode == Mode.Proxy && !(status != AppStatus.Halted && activeMode == Mode.VPN)
    }

    private suspend fun applySavedDnsToRunningService() {
        val (status, mode) = serviceStateStore.status.value
        if (status != AppStatus.Running) {
            return
        }
        serviceController.stop()
        val halted =
            withTimeoutOrNull(10.seconds) {
                serviceStateStore.status.first { it.first == AppStatus.Halted }
                true
            } == true
        if (halted) {
            when (serviceController.start(mode)) {
                is ServiceStartResult.Accepted -> Unit
                is ServiceStartResult.Rejected -> Unit
            }
        }
    }
}
