package com.poyka.ripdpi.ui.screens.profiles

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProfileQualityDecisionReason
import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.ProfileQualityStatus
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun ProfileQualityDetails(
    state: ProfileQualityUiState,
    locale: Locale,
) {
    val strings = AndroidStringResolver(LocalContext.current)
    profileQualityLines(state, strings, locale).forEach {
        Text(
            it,
            modifier = Modifier.fillMaxWidth(),
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

internal fun profileQualityLines(
    state: ProfileQualityUiState,
    strings: StringResolver,
    locale: Locale,
): List<String> =
    buildList {
        add(strings.getString(R.string.profile_quality_title))
        state.observation?.let { observation ->
            add(strings.getString(observation.reason.qualityLabel()))
            if (observation.status != ProfileQualityStatus.Unavailable &&
                observation.reason != ProfileQualityReason.ScopeChanged
            ) {
                observation.goodputBytesPerSecond?.let {
                    add(
                        strings.getString(
                            R.string.profile_quality_goodput,
                            qualityNumber(it / BytesPerKibibyte, locale),
                        ),
                    )
                }
                val bytes = observation.verifiedBytes
                val duration = observation.bodyDurationNanos
                if (bytes != null && duration != null) {
                    add(
                        strings.getString(
                            R.string.profile_quality_transfer,
                            qualityNumber(bytes.toDouble(), locale),
                            qualityNumber(duration / NanosPerSecond, locale),
                        ),
                    )
                }
                observation.baselineLatencyMillis?.let {
                    add(strings.getString(R.string.profile_quality_baseline, it))
                }
                if (observation.loadedOverlap) {
                    observation.loadedLatencyMillis?.let {
                        add(strings.getString(R.string.profile_quality_loaded, it))
                    }
                }
            }
        }
        state.budget?.let {
            add(
                strings.getString(
                    R.string.profile_quality_budget,
                    it.reservedPassBytes,
                    it.consumedWindowBytes,
                    it.maxWindowBytes,
                ),
            )
            if (it.reason in setOf(ProfileQualityReason.BudgetExhausted, ProfileQualityReason.BudgetUnavailable)) {
                add(strings.getString(it.reason.qualityLabel()))
            }
        }
        state.decision
            ?.reason
            ?.qualityLabel()
            ?.let { add(strings.getString(it)) }
        add(strings.getString(R.string.profile_quality_limits))
    }

private fun qualityNumber(
    value: Double,
    locale: Locale,
): String = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = QualityFractionDigits }.format(value)

private fun ProfileQualityReason.qualityLabel(): Int =
    when (this) {
        ProfileQualityReason.Verified -> R.string.profile_quality_verified
        ProfileQualityReason.LoadNotOverlapped -> R.string.profile_quality_no_overlap
        ProfileQualityReason.BaselineUnavailable -> R.string.profile_quality_no_baseline
        ProfileQualityReason.LoadedUnavailable -> R.string.profile_quality_no_loaded
        ProfileQualityReason.TransferFailed -> R.string.profile_quality_failed
        ProfileQualityReason.BudgetExhausted -> R.string.profile_quality_budget_exhausted
        ProfileQualityReason.BudgetUnavailable -> R.string.profile_quality_budget_unavailable
        ProfileQualityReason.ScopeChanged -> R.string.profile_quality_stale
        ProfileQualityReason.CleanupPending -> R.string.profile_quality_cleanup
    }

private fun ProfileQualityDecisionReason.qualityLabel(): Int? =
    when (this) {
        ProfileQualityDecisionReason.CollectingEvidence -> R.string.profile_quality_collecting
        ProfileQualityDecisionReason.NoQualifiedImprovement -> R.string.profile_quality_retained
        ProfileQualityDecisionReason.PromotedForGoodput -> R.string.profile_quality_promoted_goodput
        ProfileQualityDecisionReason.PromotedForLoadedLatency -> R.string.profile_quality_promoted_latency
        ProfileQualityDecisionReason.Cooldown -> R.string.profile_quality_cooldown
        ProfileQualityDecisionReason.DeferredBudget -> null
    }

private const val BytesPerKibibyte = 1024.0
private const val NanosPerSecond = 1_000_000_000.0
private const val QualityFractionDigits = 3
