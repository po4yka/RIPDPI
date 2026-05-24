package com.poyka.ripdpi.ui.components.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

// Spec: components-section-header.html — `letter-spacing: 0.72px` matched in sp.
private val SectionHeaderTracking = 0.72.sp

/**
 * Generic section header label: uppercase, muted, tracked, used to group
 * a `Column` of [com.poyka.ripdpi.ui.components.cards.SettingsRow] or other
 * card-based list items. Matches `components-section-header.html`.
 *
 * For a settings-scoped variant with built-in icon affordance, see
 * `SettingsCategoryHeader`.
 */
@Composable
fun RipDpiSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title.uppercase(),
        modifier =
            modifier
                .padding(top = RipDpiThemeTokens.spacing.md, bottom = RipDpiThemeTokens.spacing.sm)
                .semantics {
                    heading()
                    contentDescription = title
                },
        style =
            RipDpiThemeTokens.type.sectionTitle.copy(
                color = RipDpiThemeTokens.colors.mutedForeground,
                letterSpacing = SectionHeaderTracking,
                fontWeight = FontWeight.Medium,
            ),
    )
}

@Preview(showBackground = true, name = "RipDpiSectionHeader (light)")
@Composable
private fun RipDpiSectionHeaderPreviewLight() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RipDpiSectionHeader(title = "Connection")
            Text(
                text = "VPN with remote server",
                style = RipDpiThemeTokens.type.bodyEmphasis,
            )
            RipDpiSectionHeader(title = "Diagnostics")
            Text(
                text = "Run scan",
                style = RipDpiThemeTokens.type.bodyEmphasis,
            )
        }
    }
}

@Preview(showBackground = true, name = "RipDpiSectionHeader (dark)")
@Composable
private fun RipDpiSectionHeaderPreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RipDpiSectionHeader(title = "Connection")
            RipDpiSectionHeader(title = "Diagnostics")
        }
    }
}
