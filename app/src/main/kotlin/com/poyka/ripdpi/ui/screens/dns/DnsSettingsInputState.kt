package com.poyka.ripdpi.ui.screens.dns

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.utility.checkNotLocalIp

private const val dnsPortMax = 65535
private val DnsCryptPublicKeyPattern = Regex("^[0-9a-fA-F]{64}$")

internal data class DnsSettingsInputState(
    val replacementKey: DnsSettingsReplacementKey,
    val plainDnsInput: String,
    val onPlainDnsInputChange: (String) -> Unit,
    val customDohUrl: String,
    val onCustomDohUrlChange: (String) -> Unit,
    val customDotHost: String,
    val onCustomDotHostChange: (String) -> Unit,
    val customDnsCryptHost: String,
    val onCustomDnsCryptHostChange: (String) -> Unit,
    val portInput: String,
    val onPortInputChange: (String) -> Unit,
    val tlsServerNameInput: String,
    val onTlsServerNameInputChange: (String) -> Unit,
    val bootstrapInput: String,
    val onBootstrapInputChange: (String) -> Unit,
    val dnscryptProviderInput: String,
    val onDnscryptProviderInputChange: (String) -> Unit,
    val dnscryptPublicKeyInput: String,
    val onDnscryptPublicKeyInputChange: (String) -> Unit,
)

internal data class DnsSettingsReplacementKey(
    val plainDns: Any,
    val dohUrl: Any,
    val dotHost: Any,
    val dnsCryptHost: Any,
    val port: Any,
    val tlsServerName: Any,
    val bootstrap: Any,
    val dnsCryptProvider: Any,
    val dnsCryptPublicKey: Any,
)

internal data class DnsSettingsValidation(
    val trimmedPlainDns: String,
    val normalizedBootstrapIps: List<String>,
    val bootstrapIpsValid: Boolean,
    val plainDnsValid: Boolean,
    val plainDnsDirty: Boolean,
    val trimmedDohUrl: String,
    val customDohValid: Boolean,
    val customDohDirty: Boolean,
    val trimmedDotHost: String,
    val trimmedDotTlsServerName: String,
    val dotPort: Int,
    val customDotValid: Boolean,
    val customDotDirty: Boolean,
    val trimmedDnsCryptHost: String,
    val dnscryptPort: Int,
    val trimmedDnsCryptProvider: String,
    val trimmedDnsCryptPublicKey: String,
    val dnscryptPublicKeyValid: Boolean,
    val customDnsCryptValid: Boolean,
    val customDnsCryptDirty: Boolean,
)

@Composable
internal fun rememberDnsSettingsInputState(dns: DnsUiState): DnsSettingsInputState {
    var plainDnsInput by rememberSaveable(dns.dnsIp, dns.dnsMode) {
        mutableStateOf(if (dns.dnsMode == DnsModePlainUdp) dns.dnsIp else "")
    }
    var customDohUrl by rememberSaveable(dns.encryptedDnsDohUrl, dns.encryptedDnsProtocol) {
        mutableStateOf(if (dns.encryptedDnsProtocol == EncryptedDnsProtocolDoh) dns.encryptedDnsDohUrl else "")
    }
    var customDotHost by rememberSaveable(dns.encryptedDnsHost, dns.encryptedDnsProtocol) {
        mutableStateOf(if (dns.encryptedDnsProtocol == EncryptedDnsProtocolDot) dns.encryptedDnsHost else "")
    }
    var customDnsCryptHost by rememberSaveable(dns.encryptedDnsHost, dns.encryptedDnsProtocol) {
        mutableStateOf(if (dns.encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt) dns.encryptedDnsHost else "")
    }
    var portInput by rememberSaveable(dns.encryptedDnsPort, dns.encryptedDnsProtocol) {
        mutableStateOf(encryptedPortInput(dns))
    }
    var tlsServerNameInput by rememberSaveable(dns.encryptedDnsTlsServerName, dns.encryptedDnsProtocol) {
        mutableStateOf(if (dns.encryptedDnsProtocol == EncryptedDnsProtocolDot) dns.encryptedDnsTlsServerName else "")
    }
    var bootstrapInput by rememberSaveable(dns.encryptedDnsBootstrapIps, dns.encryptedDnsProtocol) {
        mutableStateOf(
            if (dns.dnsMode == DnsModeEncrypted) dns.encryptedDnsBootstrapIps.joinToString(separator = ", ") else "",
        )
    }
    var dnscryptProviderInput by rememberSaveable(
        dns.encryptedDnsDnscryptProviderName,
        dns.encryptedDnsProtocol,
    ) {
        mutableStateOf(
            if (dns.encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt) {
                dns.encryptedDnsDnscryptProviderName
            } else {
                ""
            },
        )
    }
    var dnscryptPublicKeyInput by rememberSaveable(
        dns.encryptedDnsDnscryptPublicKey,
        dns.encryptedDnsProtocol,
    ) {
        mutableStateOf(
            if (dns.encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt) {
                dns.encryptedDnsDnscryptPublicKey
            } else {
                ""
            },
        )
    }
    return DnsSettingsInputState(
        replacementKey = dns.toInputReplacementKey(),
        plainDnsInput = plainDnsInput,
        onPlainDnsInputChange = { plainDnsInput = it },
        customDohUrl = customDohUrl,
        onCustomDohUrlChange = { customDohUrl = it },
        customDotHost = customDotHost,
        onCustomDotHostChange = { customDotHost = it },
        customDnsCryptHost = customDnsCryptHost,
        onCustomDnsCryptHostChange = { customDnsCryptHost = it },
        portInput = portInput,
        onPortInputChange = { portInput = it },
        tlsServerNameInput = tlsServerNameInput,
        onTlsServerNameInputChange = { tlsServerNameInput = it },
        bootstrapInput = bootstrapInput,
        onBootstrapInputChange = { bootstrapInput = it },
        dnscryptProviderInput = dnscryptProviderInput,
        onDnscryptProviderInputChange = { dnscryptProviderInput = it },
        dnscryptPublicKeyInput = dnscryptPublicKeyInput,
        onDnscryptPublicKeyInputChange = { dnscryptPublicKeyInput = it },
    )
}

private fun DnsUiState.toInputReplacementKey() =
    DnsSettingsReplacementKey(
        plainDns = dnsMode to dnsIp,
        dohUrl = Triple(encryptedDnsProtocol, dnsProviderId, encryptedDnsDohUrl),
        dotHost = encryptedDnsProtocol to encryptedDnsHost,
        dnsCryptHost = encryptedDnsProtocol to encryptedDnsHost,
        port = encryptedDnsProtocol to encryptedDnsPort,
        tlsServerName = encryptedDnsProtocol to encryptedDnsTlsServerName,
        bootstrap = Triple(dnsMode, encryptedDnsProtocol, encryptedDnsBootstrapIps),
        dnsCryptProvider = encryptedDnsProtocol to encryptedDnsDnscryptProviderName,
        dnsCryptPublicKey = encryptedDnsProtocol to encryptedDnsDnscryptPublicKey,
    )

@Composable
internal fun rememberDnsSettingsValidation(
    dns: DnsUiState,
    input: DnsSettingsInputState,
): DnsSettingsValidation {
    val normalizedBootstrapIps = remember(input.bootstrapInput) { parseBootstrapIps(input.bootstrapInput) }
    return DnsSettingsValidation(
        dns = dns,
        input = input,
        normalizedBootstrapIps = normalizedBootstrapIps,
    )
}

internal fun selectedResolverOption(dns: DnsUiState): DnsResolverOption? =
    resolverOptions.firstOrNull {
        dns.dnsMode == DnsModeEncrypted &&
            dns.encryptedDnsProtocol == EncryptedDnsProtocolDoh &&
            it.providerId == dns.dnsProviderId
    }

private fun encryptedPortInput(dns: DnsUiState): String =
    if (dns.encryptedDnsProtocol == EncryptedDnsProtocolDot ||
        dns.encryptedDnsProtocol == EncryptedDnsProtocolDnsCrypt
    ) {
        dns.encryptedDnsPort
            .takeIf { it > 0 }
            ?.toString()
            .orEmpty()
    } else {
        ""
    }

private fun DnsSettingsValidation(
    dns: DnsUiState,
    input: DnsSettingsInputState,
    normalizedBootstrapIps: List<String>,
): DnsSettingsValidation {
    val trimmedPlainDns = input.plainDnsInput.trim()
    val trimmedDohUrl = input.customDohUrl.trim()
    val trimmedDotHost = input.customDotHost.trim()
    val trimmedDotTlsServerName = input.tlsServerNameInput.trim()
    val trimmedDnsCryptHost = input.customDnsCryptHost.trim()
    val trimmedDnsCryptProvider = input.dnscryptProviderInput.trim()
    val trimmedDnsCryptPublicKey = input.dnscryptPublicKeyInput.trim()
    val dotPort = input.portInput.toIntOrNull() ?: 0
    val dnscryptPort = input.portInput.toIntOrNull() ?: 0
    val bootstrapIpsValid = normalizedBootstrapIps.isNotEmpty() && normalizedBootstrapIps.all(::checkNotLocalIp)
    val dnscryptPublicKeyValid = trimmedDnsCryptPublicKey.matches(DnsCryptPublicKeyPattern)
    return DnsSettingsValidation(
        trimmedPlainDns = trimmedPlainDns,
        normalizedBootstrapIps = normalizedBootstrapIps,
        bootstrapIpsValid = bootstrapIpsValid,
        plainDnsValid = trimmedPlainDns.isNotEmpty() && checkNotLocalIp(trimmedPlainDns),
        plainDnsDirty = dns.dnsMode != DnsModePlainUdp || trimmedPlainDns != dns.dnsIp,
        trimmedDohUrl = trimmedDohUrl,
        customDohValid = isValidHttpsUrl(trimmedDohUrl) && bootstrapIpsValid,
        customDohDirty = dns.isCustomDohDirty(trimmedDohUrl, normalizedBootstrapIps),
        trimmedDotHost = trimmedDotHost,
        trimmedDotTlsServerName = trimmedDotTlsServerName,
        dotPort = dotPort,
        customDotValid =
            trimmedDotHost.isNotEmpty() &&
                dotPort in 1..dnsPortMax &&
                trimmedDotTlsServerName.isNotEmpty() &&
                bootstrapIpsValid,
        customDotDirty =
            dns.isCustomDotDirty(
                host = trimmedDotHost,
                port = dotPort,
                tlsServerName = trimmedDotTlsServerName,
                bootstrapIps = normalizedBootstrapIps,
            ),
        trimmedDnsCryptHost = trimmedDnsCryptHost,
        dnscryptPort = dnscryptPort,
        trimmedDnsCryptProvider = trimmedDnsCryptProvider,
        trimmedDnsCryptPublicKey = trimmedDnsCryptPublicKey,
        dnscryptPublicKeyValid = dnscryptPublicKeyValid,
        customDnsCryptValid =
            trimmedDnsCryptHost.isNotEmpty() &&
                dnscryptPort in 1..dnsPortMax &&
                trimmedDnsCryptProvider.isNotEmpty() &&
                dnscryptPublicKeyValid &&
                bootstrapIpsValid,
        customDnsCryptDirty =
            dns.isCustomDnsCryptDirty(
                host = trimmedDnsCryptHost,
                port = dnscryptPort,
                provider = trimmedDnsCryptProvider,
                publicKey = trimmedDnsCryptPublicKey,
                bootstrapIps = normalizedBootstrapIps,
            ),
    )
}

private fun DnsUiState.isCustomDohDirty(
    dohUrl: String,
    bootstrapIps: List<String>,
): Boolean =
    dnsMode != DnsModeEncrypted ||
        encryptedDnsProtocol != EncryptedDnsProtocolDoh ||
        dnsProviderId != DnsProviderCustom ||
        dohUrl != encryptedDnsDohUrl ||
        bootstrapIps != encryptedDnsBootstrapIps

private fun DnsUiState.isCustomDotDirty(
    host: String,
    port: Int,
    tlsServerName: String,
    bootstrapIps: List<String>,
): Boolean =
    dnsMode != DnsModeEncrypted ||
        encryptedDnsProtocol != EncryptedDnsProtocolDot ||
        dnsProviderId != DnsProviderCustom ||
        host != encryptedDnsHost ||
        port != encryptedDnsPort ||
        tlsServerName != encryptedDnsTlsServerName ||
        bootstrapIps != encryptedDnsBootstrapIps

private fun DnsUiState.isCustomDnsCryptDirty(
    host: String,
    port: Int,
    provider: String,
    publicKey: String,
    bootstrapIps: List<String>,
): Boolean =
    dnsMode != DnsModeEncrypted ||
        encryptedDnsProtocol != EncryptedDnsProtocolDnsCrypt ||
        dnsProviderId != DnsProviderCustom ||
        host != encryptedDnsHost ||
        port != encryptedDnsPort ||
        provider != encryptedDnsDnscryptProviderName ||
        publicKey != encryptedDnsDnscryptPublicKey ||
        bootstrapIps != encryptedDnsBootstrapIps
