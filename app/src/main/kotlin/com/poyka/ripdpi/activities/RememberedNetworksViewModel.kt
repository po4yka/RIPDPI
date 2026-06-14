package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusObserved
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusSuppressed
import com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** Upper bound on how many remembered networks the inspector renders at once. */
internal const val RememberedNetworksInspectorLimit = 200

/** Number of leading scope-hash characters surfaced as a privacy-safe scope identifier. */
private const val ScopeKeyDisplayLength = 12

private val rememberedNetworkTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US).withZone(ZoneId.systemDefault())

internal fun formatRememberedNetworkTimestamp(timestamp: Long): String =
    rememberedNetworkTimestampFormatter.format(Instant.ofEpochMilli(timestamp))

/**
 * Drives the per-network policy inspector. The screen lists every remembered network policy by a
 * privacy-safe label and exposes per-entry delete + clear-all. No raw network identifier
 * (SSID / BSSID / IP / IMEI) ever reaches this layer: [DiagnosticsRememberedPolicy.summary] is a
 * pre-sanitised [com.poyka.ripdpi.data.NetworkFingerprintSummary], and [fingerprintHash] is a
 * SHA-256 scope hash. See `.claude/rules/network-fingerprint-privacy.md`.
 */
@HiltViewModel
class RememberedNetworksViewModel
    @Inject
    constructor(
        private val rememberedPolicySource: DiagnosticsRememberedPolicySource,
    ) : ViewModel() {
        val uiState: StateFlow<RememberedNetworksUiState> =
            rememberedPolicySource
                .observePolicies(limit = RememberedNetworksInspectorLimit)
                .map { policies ->
                    RememberedNetworksUiState(
                        loading = false,
                        entries =
                            policies.map { policy ->
                                policy.toRememberedNetworkUiModel(::formatRememberedNetworkTimestamp)
                            },
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = RememberedNetworksUiState(loading = true),
                )

        fun deletePolicy(entry: RememberedNetworkUiModel) {
            viewModelScope.launch {
                rememberedPolicySource.deletePolicy(
                    DiagnosticsRememberedPolicy(
                        id = entry.id,
                        fingerprintHash = entry.fingerprintHash,
                        mode = entry.modeKey,
                        proxyConfigJson = "",
                        source = com.poyka.ripdpi.data.RememberedNetworkPolicySource.UNKNOWN,
                        status = entry.statusKey,
                        firstObservedAt = 0L,
                        updatedAt = 0L,
                    ),
                )
            }
        }

        fun clearAll() {
            viewModelScope.launch { rememberedPolicySource.clearAll() }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

data class RememberedNetworksUiState(
    val loading: Boolean = true,
    val entries: List<RememberedNetworkUiModel> = emptyList(),
) {
    val isEmpty: Boolean
        get() = !loading && entries.isEmpty()
}

enum class RememberedNetworkStatusTone { Validated, Observed, Suppressed }

/**
 * Render-ready projection of a single remembered policy. Carries only privacy-safe fields; the
 * raw [fingerprintHash] (a SHA-256 scope hash) and [modeKey]/[statusKey] storage keys are retained
 * purely so the inspector can re-issue a scoped delete.
 */
data class RememberedNetworkUiModel(
    val id: Long,
    val fingerprintHash: String,
    val modeKey: String,
    val statusKey: String,
    val scopeKeyShort: String,
    val transportKey: String,
    val validationKey: String,
    val privateDnsMode: String,
    val dnsServerCount: Int,
    val statusTone: RememberedNetworkStatusTone,
    val winningTcpStrategyFamily: String?,
    val winningQuicStrategyFamily: String?,
    val successCount: Int,
    val failureCount: Int,
    val updatedLabel: String,
)

internal fun DiagnosticsRememberedPolicy.toRememberedNetworkUiModel(
    formatTimestamp: (Long) -> String,
): RememberedNetworkUiModel =
    RememberedNetworkUiModel(
        id = id,
        fingerprintHash = fingerprintHash,
        modeKey = mode,
        statusKey = status,
        scopeKeyShort = fingerprintHash.take(ScopeKeyDisplayLength),
        transportKey = summary?.transport.orEmpty(),
        validationKey = summary?.networkState.orEmpty(),
        privateDnsMode = summary?.privateDnsMode.orEmpty(),
        dnsServerCount = summary?.dnsServerCount ?: 0,
        statusTone =
            when (status) {
                RememberedNetworkPolicyStatusValidated -> RememberedNetworkStatusTone.Validated
                RememberedNetworkPolicyStatusSuppressed -> RememberedNetworkStatusTone.Suppressed
                RememberedNetworkPolicyStatusObserved -> RememberedNetworkStatusTone.Observed
                else -> RememberedNetworkStatusTone.Observed
            },
        winningTcpStrategyFamily = winningTcpStrategyFamily?.takeIf { it.isNotBlank() },
        winningQuicStrategyFamily = winningQuicStrategyFamily?.takeIf { it.isNotBlank() },
        successCount = successCount,
        failureCount = failureCount,
        updatedLabel = formatTimestamp(updatedAt),
    )
