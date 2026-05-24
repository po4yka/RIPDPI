package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiBrandBadgeSize(
    val dp: Dp,
) {
    /** Status bar / list-row leading slot. */
    StatusBar(24.dp),

    /** Top app bar — Compact width class. */
    AppBarCompact(32.dp),

    /** Top app bar — Medium width class. */
    AppBarMedium(36.dp),

    /** Top app bar — Expanded width class. */
    AppBarExpanded(40.dp),

    /** Brand-mark lockup, e.g. with the Pixel Circle wordmark. */
    Lockup(64.dp),
}

private const val GlyphScale = 0.58f

@Composable
fun RipDpiBrandBadge(
    size: RipDpiBrandBadgeSize = RipDpiBrandBadgeSize.AppBarCompact,
    modifier: Modifier = Modifier,
    contentDescriptionText: String = "RIPDPI",
) {
    val container = RipDpiThemeTokens.colors.foreground
    val glyphTint = RipDpiThemeTokens.colors.background

    Box(
        modifier =
            modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(container, CircleShape)
                .semantics { contentDescription = contentDescriptionText },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.ic_launcher_foreground_ripdpi_clean),
            contentDescription = null,
            colorFilter =
                androidx.compose.ui.graphics.ColorFilter
                    .tint(glyphTint),
            modifier =
                Modifier
                    .fillMaxSize()
                    .scale(GlyphScale),
        )
    }
}

@Preview(showBackground = true, name = "RipDpiBrandBadge — all sizes (light)")
@Composable
private fun RipDpiBrandBadgeLightPreview() {
    RipDpiComponentPreview {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
        ) {
            RipDpiBrandBadgeSize.entries.forEach { size ->
                RipDpiBrandBadge(size = size)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
        ) {
            RipDpiBrandBadgeSize.entries.forEach { size ->
                Text(text = "${size.dp.value.toInt()}dp", style = RipDpiThemeTokens.type.caption)
            }
        }
    }
}

@Preview(showBackground = true, name = "RipDpiBrandBadge — all sizes (dark)")
@Composable
private fun RipDpiBrandBadgeDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md),
        ) {
            RipDpiBrandBadgeSize.entries.forEach { size ->
                RipDpiBrandBadge(size = size)
            }
        }
    }
}
