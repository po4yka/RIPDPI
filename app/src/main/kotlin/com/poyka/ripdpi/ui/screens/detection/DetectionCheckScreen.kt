package com.poyka.ripdpi.ui.screens.detection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner
import com.poyka.ripdpi.core.detection.DetectionStage
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant

private val colorDetected = Color(0xFFF44336)
private val colorReview = Color(0xFFFF9800)
private val colorOk = Color(0xFF4CAF50)
private val colorPending = Color(0xFF9E9E9E)

@Composable
internal fun DetectionCheckRoute(
    onBack: () -> Unit,
    viewModel: DetectionCheckViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { viewModel.onPermissionsResult() }

    DetectionCheckScreen(
        uiState = uiState,
        onStart = viewModel::startCheck,
        onStop = viewModel::stopCheck,
        onBack = onBack,
        onDismissOnboarding = viewModel::dismissOnboarding,
        onRequestPermissions = {
            when (uiState.permissionAction) {
                DetectionPermissionPlanner.Action.REQUEST,
                DetectionPermissionPlanner.Action.SHOW_RATIONALE,
                -> {
                    permissionLauncher.launch(uiState.missingPermissions.toTypedArray())
                }

                DetectionPermissionPlanner.Action.OPEN_SETTINGS -> {
                    val intent =
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    context.startActivity(intent)
                }

                DetectionPermissionPlanner.Action.NONE -> {}
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun DetectionCheckScreen(
    uiState: DetectionCheckUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    onDismissOnboarding: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current

    if (uiState.showOnboarding) {
        OnboardingDialog(
            onAllow = {
                onDismissOnboarding()
                onRequestPermissions()
            },
            onSkip = onDismissOnboarding,
        )
    }

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

            if (uiState.missingPermissions.isNotEmpty()) {
                PermissionBanner(
                    action = uiState.permissionAction,
                    onRequest = onRequestPermissions,
                )
            }

            if (uiState.isRunning) {
                RipDpiButton(
                    text = stringResource(R.string.detection_check_stop),
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                )
                uiState.progress?.let { progress ->
                    StageProgressCard(progress = progress)
                }
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
                                val clipboard =
                                    context.getSystemService(
                                        Context.CLIPBOARD_SERVICE,
                                    ) as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("Detection Report", text),
                                )
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

                CategoryCard(
                    stringResource(R.string.detection_check_category_geoip),
                    result.geoIp,
                )
                CategoryCard(
                    stringResource(R.string.detection_check_category_direct),
                    result.directSigns,
                )
                CategoryCard(
                    stringResource(R.string.detection_check_category_indirect),
                    result.indirectSigns,
                )
                CategoryCard(
                    stringResource(R.string.detection_check_category_location),
                    result.locationSignals,
                )
                BypassCard(result)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OnboardingDialog(
    onAllow: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.detection_onboarding_title)) },
        text = { Text(stringResource(R.string.detection_onboarding_body)) },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.detection_onboarding_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.detection_onboarding_skip))
            }
        },
    )
}

@Composable
private fun PermissionBanner(
    action: DetectionPermissionPlanner.Action,
    onRequest: () -> Unit,
) {
    val (text, buttonLabel) =
        when (action) {
            DetectionPermissionPlanner.Action.SHOW_RATIONALE -> {
                stringResource(R.string.detection_permission_rationale) to
                    stringResource(R.string.detection_onboarding_allow)
            }

            DetectionPermissionPlanner.Action.OPEN_SETTINGS -> {
                stringResource(R.string.detection_permission_settings) to
                    stringResource(R.string.detection_permission_open_settings)
            }

            else -> {
                stringResource(R.string.detection_permission_rationale) to
                    stringResource(R.string.detection_onboarding_allow)
            }
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            RipDpiButton(
                text = buttonLabel,
                onClick = onRequest,
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}

@Composable
private fun StageProgressCard(progress: com.poyka.ripdpi.core.detection.DetectionProgress) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Column {
                    Text(text = progress.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = progress.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val totalStages = DetectionStage.entries.size
            val completedCount = progress.completedStages.size
            LinearProgressIndicator(
                progress = { completedCount.toFloat() / totalStages },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (stage in DetectionStage.entries) {
                    val done = stage in progress.completedStages
                    val active = stage == progress.stage && !done
                    val color =
                        when {
                            done -> colorOk
                            active -> MaterialTheme.colorScheme.primary
                            else -> colorPending
                        }
                    val icon =
                        when {
                            done -> Icons.Filled.CheckCircle
                            active -> Icons.Filled.HelpOutline
                            else -> Icons.Filled.HelpOutline
                        }
                    Icon(
                        imageVector = icon,
                        contentDescription = stage.name,
                        tint = color,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun VerdictCard(verdict: Verdict) {
    val (label, color) =
        when (verdict) {
            Verdict.NOT_DETECTED -> {
                stringResource(R.string.detection_check_verdict_not_detected) to colorOk
            }

            Verdict.NEEDS_REVIEW -> {
                stringResource(R.string.detection_check_verdict_needs_review) to colorReview
            }

            Verdict.DETECTED -> {
                stringResource(R.string.detection_check_verdict_detected) to colorDetected
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
    val (icon, tint) = statusIconAndTint(category.detected, category.needsReview)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
            category.findings.forEach { finding -> FindingRow(finding) }
        }
    }
}

@Composable
private fun BypassCard(result: DetectionCheckResult) {
    val (icon, tint) = statusIconAndTint(result.bypassResult.detected, result.bypassResult.needsReview)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.detection_check_category_bypass),
                    style = MaterialTheme.typography.titleSmall,
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
            result.bypassResult.findings.forEach { finding -> FindingRow(finding) }
        }
    }
}

private fun statusIconAndTint(
    detected: Boolean,
    needsReview: Boolean,
): Pair<ImageVector, Color> =
    when {
        detected -> Icons.Filled.Error to colorDetected
        needsReview -> Icons.Filled.Warning to colorReview
        else -> Icons.Filled.CheckCircle to colorOk
    }

@Composable
private fun FindingRow(finding: Finding) {
    val color =
        when {
            finding.detected -> MaterialTheme.colorScheme.error
            finding.needsReview -> colorReview
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
