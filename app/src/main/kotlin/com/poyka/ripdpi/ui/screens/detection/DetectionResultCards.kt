package com.poyka.ripdpi.ui.screens.detection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
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
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.AutoTuneFix
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionProgress
import com.poyka.ripdpi.core.detection.DetectionStage
import com.poyka.ripdpi.core.detection.ExposureStatus
import com.poyka.ripdpi.core.detection.ExposureVerdict
import com.poyka.ripdpi.core.detection.ExposureVerdictBuilder
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.StealthScore
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.VerdictExplanation
import com.poyka.ripdpi.core.detection.VerdictNarrative
import com.poyka.ripdpi.core.detection.VerdictNarrativeRow
import com.poyka.ripdpi.core.detection.privacy.DetectionPrivacyMask
import com.poyka.ripdpi.core.detection.ui.DetectionColorVisionMode
import com.poyka.ripdpi.core.detection.ui.DetectionVisualState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.RipDpiProgressBar
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinner
import com.poyka.ripdpi.ui.components.indicators.RipDpiSpinnerSize
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private data class CategoryEntry(
    val title: String,
    val category: CategoryResult,
    val key: String,
    val icon: ImageVector,
)

private data class DetectionProbeSummary(
    val detected: Int,
    val total: Int,
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
            RipDpiSpinner(size = RipDpiSpinnerSize.Standard)
            Column {
                Text(text = progress.label, style = type.bodyEmphasis, color = colors.foreground)
                Text(text = progress.detail, style = type.caption, color = colors.mutedForeground)
            }
        }
        RipDpiProgressBar(
            progress = progress.completedStages.size.toFloat() / DetectionStage.entries.size,
        )
    }
}

@Composable
internal fun VerdictScoreCard(
    result: DetectionCheckResult,
    exposureVerdict: ExposureVerdict? = null,
    score: Int?,
    label: String?,
    explanation: VerdictExplanation? = null,
    narrative: VerdictNarrative? = null,
    colorVisionMode: DetectionColorVisionMode = DetectionColorVisionMode.OFF,
    onHeroTap: (() -> Unit)? = null,
) {
    val verdict = result.verdict
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

    RipDpiCard(
        variant = RipDpiCardVariant.Elevated,
        onClick = onHeroTap,
        modifier =
            Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .ripDpiTestTag(RipDpiTestTags.DetectionVerdict),
    ) {
        VerdictScoreHeader(
            verdictLabel = verdictLabel,
            indicatorTone = indicatorTone,
            narrative = narrative,
            visualState = verdict.toDetectionVisualState(),
            colorVisionMode = colorVisionMode,
        )
        explanation?.let {
            Text(
                text = stringResource(R.string.detection_verdict_explanation_format, it.ruleApplied, it.summary),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        VerdictOutcomeHierarchy(
            exposureVerdict =
                exposureVerdict
                    ?: score?.let { ExposureVerdictBuilder.build(result, it) },
            probeSummary = result.probeSummary(),
            score = score,
            label = label,
        )
    }
}

@Composable
private fun VerdictOutcomeHierarchy(
    exposureVerdict: ExposureVerdict?,
    probeSummary: DetectionProbeSummary,
    score: Int?,
    label: String?,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val motion = RipDpiThemeTokens.motion
    val scoreColor by animateColorAsState(
        targetValue = scoreColor(score),
        animationSpec = motion.stateTween(),
        label = "scoreColor",
    )
    val animatedScore by animateIntAsState(
        targetValue = score ?: 0,
        animationSpec = motion.emphasizedTween(),
        label = "score",
    )
    Text(
        text = exposureVerdict?.headline ?: probeSummary.fallbackHeadline(),
        style = type.bodyEmphasis,
        color = colors.foreground,
    )
    exposureVerdict?.let {
        Text(
            text = stringResource(R.string.detection_verdict_reason_format, it.reason),
            style = type.caption,
            color = colors.mutedForeground,
        )
        Text(
            text = stringResource(R.string.detection_verdict_adjust_format, it.adjustmentHint),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
    Text(
        text = stringResource(R.string.detection_probe_outcome_format, probeSummary.detected, probeSummary.total),
        style = type.caption,
        color = colors.mutedForeground,
    )
    if (score != null) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.DetectionVisibilityScale),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.detection_stealth_score_label),
                    style = type.caption,
                    color = colors.mutedForeground,
                )
                Text(
                    text = stringResource(R.string.detection_stealth_score_value, animatedScore),
                    style = type.screenTitle,
                    color = scoreColor,
                )
            }
            label?.let { Text(text = it, style = type.bodyEmphasis, color = scoreColor) }
        }
        LinearProgressIndicator(
            progress = { StealthScore.normalizedProgress(score) },
            modifier = Modifier.fillMaxWidth(),
            color = scoreColor,
            trackColor = scoreColor.copy(alpha = ScoreTrackAlpha),
        )
    }
}

private fun DetectionCheckResult.probeSummary(): DetectionProbeSummary {
    val probes =
        buildList {
            add(bypassResult.detected)
            add(geoIp.detected)
            add(directSigns.detected)
            add(indirectSigns.detected)
            add(locationSignals.detected)
            dnsLeak?.let { add(it.detected) }
            webRtcLeak?.let { add(it.detected) }
            tlsFingerprint?.let { add(it.detected) }
            timingAnalysis?.let { add(it.detected) }
            icmpSpoofing?.category?.let { add(it.detected) }
            ipComparison?.category?.let { add(it.detected) }
            rttTriangulation?.category?.let { add(it.detected) }
            cdnPulling?.category?.let { add(it.detected) }
            nativeSigns?.category?.let { add(it.detected) }
            callTransport?.category?.let { add(it.detected) }
            ipConsensus?.toCategoryResult()?.let { add(it.detected) }
        }
    return DetectionProbeSummary(detected = probes.count { it }, total = probes.size)
}

@Composable
private fun DetectionProbeSummary.fallbackHeadline(): String =
    if (detected == 0) {
        stringResource(R.string.detection_headline_ordinary_https)
    } else {
        stringResource(R.string.detection_headline_distinguishable)
    }

private const val ScoreStrongThreshold = 70
private const val ScoreReviewThreshold = 40
private const val ScoreTrackAlpha = 0.2f

@Composable
private fun scoreColor(score: Int?) =
    when {
        score == null -> RipDpiThemeTokens.colors.mutedForeground
        score >= ScoreStrongThreshold -> RipDpiThemeTokens.colors.success
        score >= ScoreReviewThreshold -> RipDpiThemeTokens.colors.warning
        else -> RipDpiThemeTokens.colors.destructive
    }

@Composable
private fun VerdictScoreHeader(
    verdictLabel: String,
    indicatorTone: StatusIndicatorTone,
    narrative: VerdictNarrative?,
    visualState: DetectionVisualState,
    colorVisionMode: DetectionColorVisionMode,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusVisualIndicator(
            state = visualState,
            mode = colorVisionMode,
            size = RipDpiThemeTokens.components.indicators.statusMarkerHero,
            contentDescription = verdictLabel,
        )
        StatusIndicator(label = verdictLabel, tone = indicatorTone)
        narrative?.let {
            StatusIndicator(
                label = it.exposureStatus.displayLabel(),
                tone = it.exposureStatus.indicatorTone(),
            )
        }
    }
}

@Composable
internal fun VerdictNarrativeCard(
    narrative: VerdictNarrative,
    privacyModeEnabled: Boolean,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.detection_verdict_narrative_title),
                style = type.sectionTitle,
                color = colors.mutedForeground,
            )
            StatusIndicator(
                label = narrative.exposureStatus.displayLabel(),
                tone = narrative.exposureStatus.indicatorTone(),
            )
        }
        Text(
            text = stringResource(R.string.detection_narrative_discovered_heading),
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = RipDpiThemeTokens.components.content.scrollableNarrativeMaxHeight)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            narrative.discoveredRows.forEach { row ->
                NarrativeRow(
                    row = row,
                    privacyModeEnabled = privacyModeEnabled,
                    icon = RipDpiIcons.Public,
                )
            }
        }
        Text(
            text = stringResource(R.string.detection_narrative_reason_heading),
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            narrative.reasonRows.forEach { row ->
                NarrativeRow(
                    row = row,
                    privacyModeEnabled = privacyModeEnabled,
                    icon = row.exposureStatus.icon(),
                )
            }
        }
        narrative.homeRoutedRoamingNote?.let { note ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = RipDpiIcons.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(RipDpiIconSizes.Compact),
                    tint = colors.warning,
                )
                Text(text = note, style = type.caption, color = colors.warning)
            }
        }
    }
}

@Composable
private fun NarrativeRow(
    row: VerdictNarrativeRow,
    privacyModeEnabled: Boolean,
    icon: ImageVector,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(RipDpiIconSizes.Compact),
            tint = row.exposureStatus.contentColor(),
        )
        Column {
            Text(text = row.label, style = type.caption, color = colors.mutedForeground)
            Text(
                text = DetectionPrivacyMask.maskIpsInText(row.value, enabled = privacyModeEnabled),
                style = type.body,
                color = row.exposureStatus.contentColor(),
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
internal fun DetectionCategoryCards(
    result: DetectionCheckResult,
    privacyModeEnabled: Boolean,
    colorVisionMode: DetectionColorVisionMode,
) {
    val expandedCategoriesSaver =
        listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() },
        )
    var expandedCategories by rememberSaveable(stateSaver = expandedCategoriesSaver) {
        mutableStateOf(emptySet())
    }
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
        privacyModeEnabled = privacyModeEnabled,
        colorVisionMode = colorVisionMode,
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
            privacyModeEnabled = privacyModeEnabled,
            colorVisionMode = colorVisionMode,
        )
    }
}

@Composable
private fun detectionCategoryEntries(result: DetectionCheckResult): List<CategoryEntry> =
    buildList {
        addPrimaryCategoryEntries(result)
        addOptionalCategoryEntries(result)
    }

@Composable
private fun MutableList<CategoryEntry>.addPrimaryCategoryEntries(result: DetectionCheckResult) {
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
}

@Composable
private fun MutableList<CategoryEntry>.addOptionalCategoryEntries(result: DetectionCheckResult) {
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
    addAdvancedCategoryEntries(result)
}

@Composable
private fun MutableList<CategoryEntry>.addAdvancedCategoryEntries(result: DetectionCheckResult) {
    addOptionalCategory(
        result.icmpSpoofing?.category,
        stringResource(R.string.detection_check_category_icmp_spoofing),
        "icmp",
        RipDpiIcons.NetworkCheck,
    )
    addOptionalCategory(
        result.ipComparison?.category,
        stringResource(R.string.detection_check_category_ip_comparison),
        "ip_comparison",
        RipDpiIcons.Public,
    )
    addOptionalCategory(
        result.rttTriangulation?.category,
        stringResource(R.string.detection_check_category_rtt_triangulation),
        "rtt_triangulation",
        RipDpiIcons.Timer,
    )
    addOptionalCategory(
        result.cdnPulling?.category,
        stringResource(R.string.detection_check_category_cdn_pulling),
        "cdn_pulling",
        RipDpiIcons.Public,
    )
    addOptionalCategory(
        result.callTransport?.category,
        stringResource(R.string.detection_check_category_call_transport),
        "call_transport",
        RipDpiIcons.Videocam,
    )
    addOptionalCategory(
        result.ipConsensus?.toCategoryResult(),
        stringResource(R.string.detection_check_category_ip_consensus),
        "ip_consensus",
        RipDpiIcons.Public,
    )
    addOptionalCategory(
        result.nativeSigns?.category,
        stringResource(R.string.detection_check_category_native_signs),
        "native_signs",
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
    privacyModeEnabled: Boolean,
    colorVisionMode: DetectionColorVisionMode,
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
    val visualState = detectionCategoryVisualState(detected = detected, needsReview = needsReview)

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
                    modifier = Modifier.size(RipDpiIconSizes.Default),
                    tint = colors.mutedForeground,
                )
                Text(text = title, style = type.bodyEmphasis)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusVisualIndicator(
                    state = visualState,
                    mode = colorVisionMode,
                    size = RipDpiThemeTokens.components.indicators.statusMarkerCompact,
                    contentDescription = statusLabel,
                )
                StatusIndicator(label = statusLabel, tone = tone)
            }
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = motion.sectionEnterTransition(),
            exit = motion.sectionExitTransition(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                findings.forEach {
                    FindingRow(
                        finding = it,
                        privacyModeEnabled = privacyModeEnabled,
                        colorVisionMode = colorVisionMode,
                    )
                }
            }
        }
    }
}

@Composable
private fun FindingRow(
    finding: Finding,
    privacyModeEnabled: Boolean,
    colorVisionMode: DetectionColorVisionMode,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val contentColor =
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
        StatusVisualIndicator(
            state =
                detectionCategoryVisualState(
                    detected = finding.detected,
                    needsReview = finding.needsReview,
                ),
            mode = colorVisionMode,
            size = RipDpiThemeTokens.components.indicators.statusMarkerLarge,
            contentDescription = dotDescription,
        )
        Text(
            text = DetectionPrivacyMask.maskIpsInText(finding.description, enabled = privacyModeEnabled),
            style = type.caption,
            color = contentColor,
        )
    }
}

@Composable
private fun ExposureStatus.displayLabel(): String =
    when (this) {
        ExposureStatus.REMOTE_ENDPOINT_DISCOVERED -> stringResource(R.string.detection_exposure_remote_endpoint)
        ExposureStatus.PUBLIC_IP_ONLY -> stringResource(R.string.detection_exposure_public_ip)
        ExposureStatus.LOCAL_PROXY_OR_API_ONLY -> stringResource(R.string.detection_exposure_local_proxy_api)
        ExposureStatus.TECHNICAL_SIGNAL_ONLY -> stringResource(R.string.detection_exposure_technical_signal)
        ExposureStatus.INSUFFICIENT_DATA -> stringResource(R.string.detection_exposure_insufficient_data)
    }

private fun ExposureStatus.indicatorTone(): StatusIndicatorTone =
    when (this) {
        ExposureStatus.REMOTE_ENDPOINT_DISCOVERED -> StatusIndicatorTone.Error
        ExposureStatus.PUBLIC_IP_ONLY -> StatusIndicatorTone.Warning
        ExposureStatus.LOCAL_PROXY_OR_API_ONLY -> StatusIndicatorTone.Warning
        ExposureStatus.TECHNICAL_SIGNAL_ONLY -> StatusIndicatorTone.Idle
        ExposureStatus.INSUFFICIENT_DATA -> StatusIndicatorTone.Active
    }

private fun ExposureStatus.icon(): ImageVector =
    when (this) {
        ExposureStatus.REMOTE_ENDPOINT_DISCOVERED -> RipDpiIcons.Public
        ExposureStatus.PUBLIC_IP_ONLY -> RipDpiIcons.Visibility
        ExposureStatus.LOCAL_PROXY_OR_API_ONLY -> RipDpiIcons.NetworkCheck
        ExposureStatus.TECHNICAL_SIGNAL_ONLY -> RipDpiIcons.Info
        ExposureStatus.INSUFFICIENT_DATA -> RipDpiIcons.Check
    }

@Composable
private fun ExposureStatus.contentColor() =
    when (this) {
        ExposureStatus.REMOTE_ENDPOINT_DISCOVERED -> RipDpiThemeTokens.colors.destructive
        ExposureStatus.PUBLIC_IP_ONLY -> RipDpiThemeTokens.colors.warning
        ExposureStatus.LOCAL_PROXY_OR_API_ONLY -> RipDpiThemeTokens.colors.warning
        ExposureStatus.TECHNICAL_SIGNAL_ONLY -> RipDpiThemeTokens.colors.info
        ExposureStatus.INSUFFICIENT_DATA -> RipDpiThemeTokens.colors.success
    }
