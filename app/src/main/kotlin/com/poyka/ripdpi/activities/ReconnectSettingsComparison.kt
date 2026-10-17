package com.poyka.ripdpi.activities

import androidx.compose.runtime.Immutable
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.data.deriveStrategyLaneFamilies
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings

/** A reference token for one saved-settings emission. It contains no settings or digest. */
@Immutable
class ReconnectSettingsIdentity {
    override fun toString(): String = "ReconnectSettingsIdentity"
}

/** Display-only saved intent. It does not predict provider selection or runtime readiness. */
@Immutable
data class HomeReconnectComparisonUiState(
    val currentSummary: String,
    val savedDns: String,
    val savedTcpStrategy: String,
)

internal fun buildReconnectComparison(
    applied: AppliedRuntimeConfiguration,
    settings: AppSettings,
    strings: StringResolver,
): HomeReconnectComparisonUiState {
    val current =
        applied.effectiveSelection.confirmedSelectionLabels(strings) +
            reconnectDnsLabel(applied.dns.mode, applied.dns.protocol, strings) +
            listOfNotNull(
                applied.strategy.takeIf { it.enabled || it.custom }?.confirmedStrategyLabel(strings),
            )
    val custom = settings.enableCmdSettings || settings.strategyChainYaml.isNotBlank()
    val family = settings.deriveStrategyLaneFamilies().tcpStrategyFamily
    return HomeReconnectComparisonUiState(
        currentSummary = current.joinToString(" · "),
        savedDns = reconnectDnsLabel(settings.dnsMode, settings.encryptedDnsProtocol, strings),
        savedTcpStrategy =
            reconnectTcpStrategyLabel(
                RuntimeConfigurationStrategy(enabled = custom || family != null, family = family, custom = custom),
                strings,
            ),
    )
}

private fun reconnectTcpStrategyLabel(
    strategy: RuntimeConfigurationStrategy,
    strings: StringResolver,
): String =
    if (!strategy.enabled && !strategy.custom && strategy.family == null) {
        strings.getString(R.string.config_local_bypass_disabled)
    } else {
        strategy.confirmedStrategyLabel(strings) ?: strings.getString(R.string.diagnostics_field_unknown)
    }

private fun reconnectDnsLabel(
    mode: String,
    protocol: String?,
    strings: StringResolver,
): String =
    strings.getString(
        when (mode) {
            DnsModePlainUdp -> {
                R.string.dns_mode_plain
            }

            DnsModeEncrypted -> {
                when (protocol) {
                    "doh" -> R.string.dns_protocol_doh
                    "dot" -> R.string.dns_protocol_dot
                    "dnscrypt" -> R.string.dns_protocol_dnscrypt
                    "doq" -> R.string.dns_protocol_doq
                    "odoh" -> R.string.dns_protocol_odoh
                    else -> R.string.diagnostics_field_unknown
                }
            }

            else -> {
                R.string.diagnostics_field_unknown
            }
        },
    )
