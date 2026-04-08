package com.poyka.ripdpi.ui.screens.detection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant

@Composable
internal fun DetectionCheckRoute(
    onBack: () -> Unit,
    viewModel: DetectionCheckViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DetectionCheckScreen(
        uiState = uiState,
        onStart = viewModel::startCheck,
        onStop = viewModel::stopCheck,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetectionCheckScreen(
    uiState: DetectionCheckUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_detection_check)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.detection_check_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (uiState.isRunning) {
                RipDpiButton(
                    text = stringResource(R.string.detection_check_stop),
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                )
                ProgressCard(stage = uiState.progressStage, detail = uiState.progressDetail)
            } else {
                RipDpiButton(
                    text = stringResource(R.string.detection_check_start),
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            uiState.error?.let { error ->
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            uiState.result?.let { result ->
                VerdictCard(result.verdict)

                if (uiState.recommendations.isNotEmpty()) {
                    RecommendationsCard(uiState.recommendations)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RipDpiButton(
                        text = stringResource(R.string.detection_check_copy),
                        onClick = {
                            uiState.reportText?.let { text ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Detection Report", text))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        variant = RipDpiButtonVariant.Outline,
                    )
                    RipDpiButton(
                        text = stringResource(R.string.detection_check_share),
                        onClick = {
                            uiState.reportText?.let { text ->
                                val intent =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        variant = RipDpiButtonVariant.Outline,
                    )
                }

                CategoryCard(stringResource(R.string.detection_check_category_geoip), result.geoIp)
                CategoryCard(stringResource(R.string.detection_check_category_direct), result.directSigns)
                CategoryCard(stringResource(R.string.detection_check_category_indirect), result.indirectSigns)
                CategoryCard(stringResource(R.string.detection_check_category_location), result.locationSignals)
                BypassCard(result)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProgressCard(
    stage: String,
    detail: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Column {
                Text(text = stage, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VerdictCard(verdict: Verdict) {
    val (label, color) =
        when (verdict) {
            Verdict.NOT_DETECTED -> {
                stringResource(R.string.detection_check_verdict_not_detected) to
                    Color(0xFF4CAF50)
            }

            Verdict.NEEDS_REVIEW -> {
                stringResource(R.string.detection_check_verdict_needs_review) to
                    Color(0xFFFF9800)
            }

            Verdict.DETECTED -> {
                stringResource(R.string.detection_check_verdict_detected) to
                    Color(0xFFF44336)
            }
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Verdict",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
    }
}

@Composable
private fun RecommendationsCard(recommendations: List<Recommendation>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.detection_check_recommendations),
                style = MaterialTheme.typography.titleSmall,
            )
            for (rec in recommendations) {
                Column {
                    Text(
                        text = rec.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = rec.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    title: String,
    category: CategoryResult,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                StatusBadge(detected = category.detected, needsReview = category.needsReview)
            }
            category.findings.forEach { finding ->
                FindingRow(finding)
            }
        }
    }
}

@Composable
private fun BypassCard(result: DetectionCheckResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.detection_check_category_bypass),
                    style = MaterialTheme.typography.titleSmall,
                )
                StatusBadge(
                    detected = result.bypassResult.detected,
                    needsReview = result.bypassResult.needsReview,
                )
            }
            result.bypassResult.findings.forEach { finding ->
                FindingRow(finding)
            }
        }
    }
}

@Composable
private fun FindingRow(finding: Finding) {
    val color =
        when {
            finding.detected -> MaterialTheme.colorScheme.error
            finding.needsReview -> Color(0xFFFF9800)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val prefix =
        when {
            finding.detected -> "!"
            finding.needsReview -> "?"
            else -> "-"
        }
    Text(
        text = "$prefix ${finding.description}",
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
private fun StatusBadge(
    detected: Boolean,
    needsReview: Boolean,
) {
    val (text, color) =
        when {
            detected -> "DETECTED" to Color(0xFFF44336)
            needsReview -> "REVIEW" to Color(0xFFFF9800)
            else -> "OK" to Color(0xFF4CAF50)
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}
