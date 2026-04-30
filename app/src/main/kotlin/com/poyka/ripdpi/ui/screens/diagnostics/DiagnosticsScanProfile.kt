package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.CompletedProbeUiModel
import com.poyka.ripdpi.activities.DiagnosticsDiagnosisUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsProgressUiModel
import com.poyka.ripdpi.activities.DiagnosticsRemediationActionKindUiModel
import com.poyka.ripdpi.activities.DiagnosticsResolverRecommendationUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanWorkflowBadgeUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanWorkflowPresentationUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeProgressLaneUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportPresentationUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeWinningCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeWinningPathUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsWorkflowRestrictionActionKindUiModel
import com.poyka.ripdpi.activities.DnsBaselineStatus
import com.poyka.ripdpi.activities.DpiFailureClass
import com.poyka.ripdpi.activities.PhaseState
import com.poyka.ripdpi.activities.PhaseStepUiModel
import com.poyka.ripdpi.activities.ScanNetworkContextUiModel
import com.poyka.ripdpi.activities.StrategyCandidateTimelineEntryUiModel
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditAssessment
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidenceLevel
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.DiagnosticsRemediationLadderCard
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricPill
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricSurface
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.indicators.ripDpiMetricToneStyle
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun CompactProfileRow(
    profile: com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel?,
    onChangeProfile: () -> Unit,
) {
    RipDpiCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = profile?.name ?: stringResource(R.string.diagnostics_profiles_title),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                if (profile != null) {
                    Text(
                        text = displayFamilyLabel(profile.family),
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                }
                if (profile?.requiresExplicitConsent == true) {
                    Text(
                        text = stringResource(R.string.diagnostics_profile_explicit_consent_required),
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = RipDpiThemeTokens.colors.warning,
                    )
                }
            }
            RipDpiButton(
                text = stringResource(R.string.diagnostics_profile_change_action),
                onClick = onChangeProfile,
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}

@Composable
internal fun ProfilePickerContent(
    profiles: List<com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel>,
    selectedProfileId: String?,
    onSelectProfile: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    Text(
        text = stringResource(R.string.diagnostics_profiles_title).uppercase(),
        style = RipDpiThemeTokens.type.sectionTitle,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    Text(
        text = stringResource(R.string.diagnostics_profiles_body),
        style = RipDpiThemeTokens.type.secondaryBody,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        profiles.groupBy { it.family }.forEach { (family, familyProfiles) ->
            Text(
                text = displayFamilyLabel(family),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            familyProfiles.forEach { profile ->
                DiagnosticsProfileCard(
                    profile = profile,
                    selected = profile.id == selectedProfileId,
                    onClick = { onSelectProfile(profile.id) },
                )
            }
        }
    }
}

@Composable
@ReadOnlyComposable
private fun displayFamilyLabel(family: com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily): String =
    when (family) {
        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.GENERAL -> {
            stringResource(
                R.string.diagnostics_family_general,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.WEB_CONNECTIVITY -> {
            stringResource(
                R.string.diagnostics_family_web_connectivity,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.MESSAGING -> {
            stringResource(
                R.string.diagnostics_family_messaging,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.CIRCUMVENTION -> {
            stringResource(
                R.string.diagnostics_family_adaptation,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.THROTTLING -> {
            stringResource(
                R.string.diagnostics_family_throttling,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.DPI_FULL -> {
            stringResource(
                R.string.diagnostics_family_network_full,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.AUTOMATIC_PROBING -> {
            stringResource(
                R.string.diagnostics_family_automatic_probing,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.AUTOMATIC_AUDIT -> {
            stringResource(
                R.string.diagnostics_family_automatic_audit,
            )
        }
    }
