package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class RipDpiExportConsentItem(
    val label: String,
    val detail: String,
    val warn: Boolean = false,
)

@Composable
fun RipDpiExportConsentDialog(
    fileName: String,
    fileSize: String,
    packetCount: Int,
    contents: ImmutableList<RipDpiExportConsentItem>,
    onDismiss: () -> Unit,
    onExport: (redactEndpoints: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    redactEndpointsDefault: Boolean = true,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val shapes = RipDpiThemeTokens.shapes
    val type = RipDpiThemeTokens.type
    var redact by rememberSaveable { mutableStateOf(redactEndpointsDefault) }

    RipDpiDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.vpn_export_consent_title_format, fileName),
        modifier = modifier,
        dismissAction =
            RipDpiDialogAction(
                label = stringResource(R.string.vpn_export_consent_cancel),
                onClick = onDismiss,
            ),
        confirmAction =
            RipDpiDialogAction(
                label = stringResource(R.string.vpn_export_consent_confirm),
                onClick = { onExport(redact) },
            ),
        visuals =
            RipDpiDialogVisuals(
                message = null,
                tone = RipDpiDialogTone.Default,
                icon = RipDpiIcons.Warning,
            ),
    ) {
        Text(
            text =
                stringResource(
                    R.string.vpn_export_consent_meta_format,
                    fileSize,
                    packetCount,
                ),
            style = type.caption,
            color = colors.mutedForeground,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.inputBackground,
            shape = shapes.lg,
        ) {
            Column(
                modifier = Modifier.padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                contents.forEach { item ->
                    RipDpiExportConsentRow(item = item)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = redact,
                onCheckedChange = { redact = it },
                colors =
                    CheckboxDefaults.colors(
                        checkedColor = colors.foreground,
                        uncheckedColor = colors.outline,
                        checkmarkColor = colors.background,
                    ),
            )
            Text(
                text = stringResource(R.string.vpn_export_consent_redact_label),
                style = type.body,
                color = colors.foreground,
            )
        }
    }
}

@Composable
private fun RipDpiExportConsentRow(item: RipDpiExportConsentItem) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val tint = if (item.warn) colors.warning else colors.success
    val icon = if (item.warn) RipDpiIcons.Warning else RipDpiIcons.Check

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(RipDpiIconSizes.Small),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = item.label,
                style = type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = item.detail,
                style = type.smallLabel,
                color = colors.mutedForeground,
            )
        }
    }
}

private val sampleConsentItems =
    persistentListOf(
        RipDpiExportConsentItem(
            label = "Packet headers",
            detail = "Always present",
        ),
        RipDpiExportConsentItem(
            label = "TLS ClientHello SNI",
            detail = "Hostnames visible",
            warn = true,
        ),
        RipDpiExportConsentItem(
            label = "Endpoint IPs",
            detail = "Redactable to 0.0.0.0 below",
            warn = true,
        ),
        RipDpiExportConsentItem(
            label = "Strategy fingerprint",
            detail = "Profile + presets",
        ),
    )

@Preview(showBackground = true)
@Composable
private fun RipDpiExportConsentDialogLightPreview() {
    RipDpiComponentPreview {
        RipDpiExportConsentDialog(
            fileName = "ripdpi-2026-05-19.pcap",
            fileSize = "128 KiB",
            packetCount = 482,
            contents = sampleConsentItems,
            onDismiss = {},
            onExport = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiExportConsentDialogDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiExportConsentDialog(
            fileName = "ripdpi-2026-05-19.pcap",
            fileSize = "128 KiB",
            packetCount = 482,
            contents = sampleConsentItems,
            onDismiss = {},
            onExport = {},
        )
    }
}
