package com.poyka.ripdpi.ui.screens.blockcheck

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.BlockLayer
import com.poyka.ripdpi.core.detection.BypassStrategyClass
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.diagnostics.RankedStrategyProbeResult
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val PercentScale = 100

@Composable
fun BlockcheckRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BlockcheckViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportSubject = stringResource(R.string.title_blockcheck)
    val exportTitle = stringResource(R.string.blockcheck_export_title)
    // Resolve the (dynamic) message resource in composition via stringResource rather
    // than LocalContext.getString in the effect (LocalContextGetResourceValueCall).
    val messageRes = state.messageRes
    val resolvedMessage = if (messageRes != null) stringResource(messageRes) else state.message
    LaunchedEffect(resolvedMessage) {
        resolvedMessage?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    BlockcheckScreen(
        state = state,
        onBack = onBack,
        onDomainsChanged = viewModel::updateDomainsText,
        onRun = viewModel::startProbe,
        onCancel = viewModel::cancelProbe,
        onApplyBest = viewModel::applyBestStrategy,
        onApplyRecommended = viewModel::applyRecommendedStrategy,
        onExport = {
            val report = viewModel.exportReport()
            val intent =
                Intent(Intent.ACTION_SEND)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_TEXT, report)
                    .putExtra(Intent.EXTRA_SUBJECT, exportSubject)
            context.startActivity(Intent.createChooser(intent, exportTitle))
        },
        modifier = modifier,
    )
}

@Composable
internal fun BlockcheckScreen(
    state: BlockcheckUiState,
    onBack: () -> Unit,
    onDomainsChanged: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onApplyBest: () -> Unit,
    onApplyRecommended: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiContentScreenScaffold(
        title = stringResource(R.string.title_blockcheck),
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.Blockcheck)),
    ) {
        RipDpiCard {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                BlockcheckHeader(state = state, onRun = onRun, onCancel = onCancel)
                RipDpiTextField(
                    value = state.domains.joinToString("\n"),
                    onValueChange = onDomainsChanged,
                    decoration =
                        RipDpiTextFieldDecoration(
                            label = stringResource(R.string.blockcheck_domains_label),
                        ),
                    behavior = RipDpiTextFieldBehavior(singleLine = false, minHeight = 96.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.isRunning) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                }
                state.message?.let {
                    Text(text = it, style = RipDpiThemeTokens.type.secondaryBody)
                }
            }
        }
        if (state.noStrategiesRegistered) {
            RipDpiCard {
                Text(
                    text = stringResource(R.string.blockcheck_no_strategies),
                    style = RipDpiThemeTokens.type.body,
                )
            }
        }
        if (state.dnsTamperDetected) {
            RipDpiCard {
                Text(
                    text = stringResource(R.string.blockcheck_dns_warning),
                    color = RipDpiThemeTokens.colors.destructive,
                    style = RipDpiThemeTokens.type.secondaryBody,
                )
            }
        }
        BlockcheckDiagnosisCard(state = state, onApplyRecommended = onApplyRecommended)
        BlockcheckResultsCard(state = state)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
            RipDpiButton(
                text = stringResource(R.string.blockcheck_apply_best),
                onClick = onApplyBest,
                enabled = state.bestStrategy != null && !state.isRunning,
                modifier = Modifier.weight(1f),
            )
            RipDpiButton(
                text = stringResource(R.string.blockcheck_export),
                onClick = onExport,
                enabled = state.results.isNotEmpty(),
                variant = RipDpiButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BlockcheckDiagnosisCard(
    state: BlockcheckUiState,
    onApplyRecommended: () -> Unit,
) {
    val primary = state.primaryDiagnosis
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = stringResource(R.string.blockcheck_diagnosis_title),
                style = RipDpiThemeTokens.type.sheetTitle,
            )
            if (primary == null) {
                Text(
                    text = stringResource(R.string.blockcheck_diagnosis_empty),
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            } else {
                state.diagnoses.forEach { diagnosis ->
                    BlockcheckDiagnosisRow(diagnosis)
                }
                RipDpiButton(
                    text =
                        state.recommendedStrategyLabel?.let { strategy ->
                            stringResource(R.string.blockcheck_try_recommended_strategy_format, strategy)
                        } ?: stringResource(R.string.blockcheck_try_recommended_strategy),
                    onClick = onApplyRecommended,
                    enabled = state.recommendedStrategyId != null && !state.isRunning,
                    variant = RipDpiButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BlockcheckDiagnosisRow(diagnosis: BlockcheckSiteDiagnosis) {
    Column {
        Text(
            text =
                stringResource(
                    R.string.blockcheck_diagnosis_layer_format,
                    diagnosis.domain,
                    diagnosis.diagnosis.layer.label(),
                ),
            style = RipDpiThemeTokens.type.body,
        )
        Text(
            text =
                stringResource(
                    R.string.blockcheck_diagnosis_strategy_format,
                    diagnosis.diagnosis.bypassClass.label(),
                    diagnosis.diagnosis.confidence.label(),
                ),
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

@Composable
private fun BlockcheckHeader(
    state: BlockcheckUiState,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.blockcheck_status_format, state.results.size, state.totalExpectedResults),
            style = RipDpiThemeTokens.type.sheetTitle,
            modifier = Modifier.weight(1f),
        )
        RipDpiButton(
            text =
                if (state.isRunning) {
                    stringResource(R.string.blockcheck_cancel)
                } else {
                    stringResource(R.string.blockcheck_run)
                },
            onClick = if (state.isRunning) onCancel else onRun,
            variant = if (state.isRunning) RipDpiButtonVariant.Outline else RipDpiButtonVariant.Primary,
        )
    }
}

@Composable
private fun BlockLayer.label(): String =
    when (this) {
        BlockLayer.DNS_POISONING -> stringResource(R.string.blockcheck_layer_dns_poisoning)
        BlockLayer.SNI_BASED_RESET -> stringResource(R.string.blockcheck_layer_sni_reset)
        BlockLayer.IP_BLOCK -> stringResource(R.string.blockcheck_layer_ip_block)
        BlockLayer.TLS_HANDSHAKE_INTERFERENCE -> stringResource(R.string.blockcheck_layer_tls_handshake)
        BlockLayer.HTTP_BLOCKPAGE -> stringResource(R.string.blockcheck_layer_http_blockpage)
        BlockLayer.UNKNOWN -> stringResource(R.string.blockcheck_layer_unknown)
    }

@Composable
private fun BypassStrategyClass.label(): String =
    when (this) {
        BypassStrategyClass.ENCRYPTED_DNS -> stringResource(R.string.blockcheck_bypass_encrypted_dns)
        BypassStrategyClass.TLS_RECORD_SPLIT -> stringResource(R.string.blockcheck_bypass_tls_record_split)
        BypassStrategyClass.FAKE_PACKET_TTL -> stringResource(R.string.blockcheck_bypass_fake_packet_ttl)
        BypassStrategyClass.HOST_HEADER_SPLIT -> stringResource(R.string.blockcheck_bypass_host_header_split)
        BypassStrategyClass.STRATEGY_PROBE_WINNER -> stringResource(R.string.blockcheck_bypass_strategy_probe_winner)
    }

@Composable
private fun EvidenceConfidence.label(): String =
    when (this) {
        EvidenceConfidence.LOW -> stringResource(R.string.blockcheck_confidence_low)
        EvidenceConfidence.MEDIUM -> stringResource(R.string.blockcheck_confidence_medium)
        EvidenceConfidence.HIGH -> stringResource(R.string.blockcheck_confidence_high)
    }

@Composable
private fun BlockcheckResultsCard(state: BlockcheckUiState) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                text = stringResource(R.string.blockcheck_results_title),
                style = RipDpiThemeTokens.type.sheetTitle,
            )
            if (state.rankedStrategies.isEmpty()) {
                Text(
                    text = stringResource(R.string.blockcheck_empty_results),
                    style = RipDpiThemeTokens.type.secondaryBody,
                )
            } else {
                state.rankedStrategies.forEachIndexed { index, result ->
                    BlockcheckRankedRow(result = result, isBest = index == 0)
                }
            }
        }
    }
}

@Composable
private fun BlockcheckRankedRow(
    result: RankedStrategyProbeResult,
    isBest: Boolean,
) {
    val spacing = RipDpiThemeTokens.spacing
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.xs),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = result.strategyLabel, style = RipDpiThemeTokens.type.body)
            Text(
                text =
                    stringResource(
                        R.string.blockcheck_result_detail_format,
                        result.successes,
                        result.total,
                        result.averageLatencyMs,
                    ),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        Text(
            text =
                if (isBest) {
                    stringResource(R.string.blockcheck_best_badge)
                } else {
                    stringResource(R.string.percent_value, (result.successRate * PercentScale).toInt())
                },
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = if (isBest) RipDpiThemeTokens.colors.info else RipDpiThemeTokens.colors.foreground,
        )
    }
}
