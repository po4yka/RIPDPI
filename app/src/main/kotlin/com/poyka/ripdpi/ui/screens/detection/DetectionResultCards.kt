package com.poyka.ripdpi.ui.screens.detection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.AutoTuneFix
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionProgress
import com.poyka.ripdpi.core.detection.DetectionStage
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.StealthScore
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private data class CategoryEntry(
    val title: String,
    val category: CategoryResult,
    val key: String,
    val icon: ImageVector,
)

@Composable
internal fun StageProgressCard(progress: DetectionProgress) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Elevated) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = colors.accent,
            )
            Column {
                Text(text = progress.label, style = type.bodyEmphasis, color = colors.foreground)
                Text(text = progress.detail, style = type.caption, color = colors.mutedForeground)
            }
        }
        LinearProgressIndicator(
            progress = { progress.completedStages.size.toFloat() / DetectionStage.entries.size },
            modifier = Modifier.fillMaxWidth(),
            color = colors.accent,
            trackColor = colors.muted,
        )
    }
}

@Composable
internal fun VerdictScoreCard(
    verdict: Verdict,
    score: Int?,
    label: String?,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val motion = RipDpiThemeTokens.motion

    val (verdictLabel, indicatorTone) =
        when (verdict) {
            Verdict.NOT_DETECTED -> {
                stringResource(R.string.detection_check_verdict_not_detected) to StatusIndicatorTone.Active
            }

            Verdict.NEEDS_REVIEW -> {
                stringResource(R.string.detection_check_verdict_needs_review) to StatusIndicatorTone.Warning
            }

            Verdict.DETECTED -> {
                stringResource(R.string.detection_check_verdict_detected) to StatusIndicatorTone.Error
            }
        }

    val scoreColor by animateColorAsState(
        targetValue =
            when {
                score == null -> colors.mutedForeground
                score >= 70 -> colors.success
                score >= 40 -> colors.warning
                else -> colors.destructive
            },
        animationSpec = motion.stateTween(),
        label = "scoreColor",
    )
    val animatedScore by animateIntAsState(
        targetValue = score ?: 0,
        animationSpec = motion.emphasizedTween(),
        label = "score",
    )

    RipDpiCard(
        variant = RipDpiCardVariant.Elevated,
        modifier =
            Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .ripDpiTestTag(RipDpiTestTags.DetectionVerdict),
    ) {
        StatusIndicator(label = verdictLabel, tone = indicatorTone)
        if (score != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.detection_stealth_score),
                        style = type.caption,
                        color = colors.mutedForeground,
                    )
                    Text(
                        text = "$animatedScore",
                        style = type.screenTitle,
                        color = scoreColor,
                    )
                }
                label?.let {
                    Text(text = it, style = type.bodyEmphasis, color = scoreColor)
                }
            }
            LinearProgressIndicator(
                progress = { StealthScore.normalizedProgress(score) },
                modifier = Modifier.fillMaxWidth(),
                color = scoreColor,
                trackColor = scoreColor.copy(alpha = 0.2f),
            )
        }
    }
}

@Composable
internal fun AutoTuneCard(
    fixes: List<AutoTuneFix>,
    onApplyAll: () -> Unit,
    applyTestTag: String? = null,
) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        Text(
            text = stringResource(R.string.detection_auto_tune_title).uppercase(),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        for (fix in fixes) {
            Text(text = fix.title, style = type.body, color = colors.foreground)
        }
        RipDpiButton(
            text = stringResource(R.string.detection_auto_tune_apply),
            onClick = onApplyAll,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(applyTestTag),
        )
    }
}

@Composable
internal fun DetectionRecommendations(recommendations: List<Recommendation>) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        Text(
            text = stringResource(R.string.detection_check_recommendations).uppercase(),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        for (rec in recommendations) {
            Column {
                Text(text = rec.title, style = type.bodyEmphasis, color = colors.foreground)
                Text(text = rec.description, style = type.caption, color = colors.mutedForeground)
            }
        }
    }
}

@Composable
internal fun DetectionCategoryCards(result: DetectionCheckResult) {
    var expandedCategories by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val categories = detectionCategoryEntries(result)

    CollapsibleCard(
        title = stringResource(R.string.detection_check_category_bypass),
        icon = RipDpiIcons.Shield,
        detected = result.bypassResult.detected,
        needsReview = result.bypassResult.needsReview,
        key = "bypass",
        expandedCategories = expandedCategories,
        onToggle = { expandedCategories = it },
        findings = result.bypassResult.findings,
    )

    for (entry in categories) {
        CollapsibleCard(
            title = entry.title,
            icon = entry.icon,
            detected = entry.category.detected,
            needsReview = entry.category.needsReview,
            key = entry.key,
            expandedCategories = expandedCategories,
            onToggle = { expandedCategories = it },
            findings = entry.category.findings,
        )
    }
}

@Composable
private fun detectionCategoryEntries(result: DetectionCheckResult): List<CategoryEntry> =
    buildList {
        add(
            CategoryEntry(
                stringResource(R.string.detection_check_category_geoip),
                result.geoIp,
                "geoip",
                RipDpiIcons.Public,
            ),
        )
        add(
            CategoryEntry(
                stringResource(R.string.detection_check_category_direct),
                result.directSigns,
                "direct",
                RipDpiIcons.Visibility,
            ),
        )
        add(
            CategoryEntry(
                stringResource(R.string.detection_check_category_indirect),
                result.indirectSigns,
                "indirect",
                RipDpiIcons.NetworkCheck,
            ),
        )
        add(
            CategoryEntry(
                stringResource(R.string.detection_check_category_location),
                result.locationSignals,
                "location",
                RipDpiIcons.LocationOn,
            ),
        )
        addOptionalCategory(
            result.dnsLeak,
            stringResource(R.string.detection_check_category_dns_leak),
            "dns",
            RipDpiIcons.Dns,
        )
        addOptionalCategory(
            result.webRtcLeak,
            stringResource(R.string.detection_check_category_webrtc),
            "webrtc",
            RipDpiIcons.Videocam,
        )
        addOptionalCategory(
            result.tlsFingerprint,
            stringResource(R.string.detection_check_category_tls),
            "tls",
            RipDpiIcons.Lock,
        )
        addOptionalCategory(
            result.timingAnalysis,
            stringResource(R.string.detection_check_category_timing),
            "timing",
            RipDpiIcons.Timer,
        )
        addOptionalCategory(
            result.icmpSpoofing?.category,
            stringResource(R.string.detection_check_category_icmp_spoofing),
            "icmp",
            RipDpiIcons.NetworkCheck,
        )
    }

private fun MutableList<CategoryEntry>.addOptionalCategory(
    category: CategoryResult?,
    title: String,
    key: String,
    icon: ImageVector,
) {
    category?.let { add(CategoryEntry(title, it, key, icon)) }
}

@Composable
private fun CollapsibleCard(
    title: String,
    icon: ImageVector,
    detected: Boolean,
    needsReview: Boolean,
    key: String,
    expandedCategories: Set<String>,
    onToggle: (Set<String>) -> Unit,
    findings: List<Finding>,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val motion = RipDpiThemeTokens.motion
    val spacing = RipDpiThemeTokens.spacing

    val tone =
        when {
            detected -> StatusIndicatorTone.Error
            needsReview -> StatusIndicatorTone.Warning
            else -> StatusIndicatorTone.Active
        }
    val statusLabel =
        when {
            detected -> stringResource(R.string.detection_status_detected)
            needsReview -> stringResource(R.string.detection_status_review)
            else -> stringResource(R.string.detection_status_ok)
        }
    val isExpanded = key in expandedCategories || detected || needsReview

    RipDpiCard(
        variant = RipDpiCardVariant.Outlined,
        onClick = {
            onToggle(
                if (key in expandedCategories) {
                    expandedCategories - key
                } else {
                    expandedCategories + key
                },
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.mutedForeground,
                )
                Text(text = title, style = type.bodyEmphasis)
            }
            StatusIndicator(label = statusLabel, tone = tone)
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = motion.sectionEnterTransition(),
            exit = motion.sectionExitTransition(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                findings.forEach { FindingRow(it) }
            }
        }
    }
}

@Composable
private fun FindingRow(finding: Finding) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val dotColor =
        when {
            finding.detected -> colors.destructive
            finding.needsReview -> colors.warning
            else -> colors.mutedForeground
        }
    val dotDescription =
        when {
            finding.detected -> stringResource(R.string.detection_finding_detected)
            finding.needsReview -> stringResource(R.string.detection_finding_review)
            else -> stringResource(R.string.detection_finding_ok)
        }
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            modifier =
                Modifier
                    .size(8.dp)
                    .semantics { contentDescription = dotDescription },
        ) {
            drawCircle(color = dotColor)
        }
        Text(
            text = finding.description,
            style = type.caption,
            color = dotColor,
        )
    }
}
