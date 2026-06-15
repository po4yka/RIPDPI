package com.poyka.ripdpi.ui.components.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.theme.RipDpiExtendedColors
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import java.util.Locale

private const val LegendKeyWeight = 0.32f
private const val LegendValueWeight = 0.68f

/**
 * Row in the fragment payload legend rendered by [RipDpiLinkPreviewCard].
 *
 * Values are [AnnotatedString] so per-row spans (foreground highlights,
 * inline-code chips) survive without leaking string-resource reads into
 * the component layer.
 */
data class RipDpiLinkPreviewRow(
    val key: String,
    val value: AnnotatedString,
)

/**
 * Pre-share transparency view for RIPDPI's self-generated share URL.
 * The "link" being previewed is the URL the app already constructs
 * locally via `DiagnosticShareLinkUrl.build`; no remote fetch happens.
 *
 * @param schemeLabel URL scheme prefix rendered in muted-foreground (e.g. `https://`).
 * @param hostLabel Host + path rendered in foreground (e.g. `po4yka.github.io/RIPDPI/share`).
 * @param queryLabel Query string rendered in muted-foreground (e.g. `?v=1`).
 * @param fragmentLabel Base64 fragment rendered with the warning-container highlight.
 * @param fragmentTruncatedHint Optional overflow marker ("...") appended in muted-foreground.
 * @param eyebrowLink Section label for the URL block.
 * @param eyebrowPayload Section label for the legend block.
 * @param copyLabel Pill-button label.
 * @param rows Legend rows.
 * @param privacyNoteTitle Privacy banner title.
 * @param privacyNoteMessage Privacy banner message.
 * @param privacyNoteEmphasis Substring of [privacyNoteMessage] to render with
 * foreground emphasis. Must be a literal substring; if absent, the banner
 * renders the message unstyled.
 */
data class RipDpiLinkPreviewState(
    val schemeLabel: String,
    val hostLabel: String,
    val queryLabel: String,
    val fragmentLabel: String,
    val fragmentTruncatedHint: String? = null,
    val eyebrowLink: String,
    val eyebrowPayload: String,
    val copyLabel: String,
    val rows: ImmutableList<RipDpiLinkPreviewRow>,
    val privacyNoteTitle: String,
    val privacyNoteMessage: String,
    val privacyNoteEmphasis: String? = null,
)

/**
 * Matches `docs/design/rds/preview/share-link-preview.html`. Stacks
 * three sections: the URL frame with a copy pill, the fragment payload
 * legend, and an info-tone privacy reassurance.
 */
@Composable
fun RipDpiLinkPreviewCard(
    state: RipDpiLinkPreviewState,
    modifier: Modifier = Modifier,
    onCopy: () -> Unit = {},
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(modifier = modifier, variant = RipDpiCardVariant.Outlined) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            UrlBlock(state = state, onCopy = onCopy)
            LegendBlock(state = state)
            PrivacyNote(state = state)
        }
    }
}

@Composable
private fun UrlBlock(
    state: RipDpiLinkPreviewState,
    onCopy: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val shapes = RipDpiThemeTokens.shapes
    val type = RipDpiThemeTokens.type
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = state.eyebrowLink.uppercase(Locale.getDefault()),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shapes.lg,
            color = colors.background,
            border = BorderStroke(RipDpiStroke.Thin, colors.cardBorder),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.lg, vertical = spacing.md),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = buildUrlAnnotated(state = state, colors = colors),
                        style = type.monoValue,
                        color = colors.foreground,
                        softWrap = false,
                        maxLines = 1,
                    )
                }
                Surface(
                    shape = shapes.full,
                    color = colors.foreground,
                    modifier =
                        Modifier.ripDpiClickable(
                            role = Role.Button,
                            onClickLabel = state.copyLabel,
                            onClick = onCopy,
                        ),
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
                        text = state.copyLabel.uppercase(Locale.getDefault()),
                        style = type.smallLabel,
                        color = colors.background,
                    )
                }
            }
        }
    }
}

private fun buildUrlAnnotated(
    state: RipDpiLinkPreviewState,
    colors: RipDpiExtendedColors,
): AnnotatedString =
    buildAnnotatedString {
        withStyle(SpanStyle(color = colors.mutedForeground)) {
            append(state.schemeLabel)
        }
        withStyle(SpanStyle(color = colors.foreground)) {
            append(state.hostLabel)
        }
        withStyle(SpanStyle(color = colors.mutedForeground)) {
            append(state.queryLabel)
        }
        withStyle(SpanStyle(color = colors.mutedForeground)) {
            append("#")
        }
        withStyle(
            SpanStyle(
                color = colors.foreground,
                background = colors.warningContainer,
            ),
        ) {
            append(state.fragmentLabel)
        }
        val hint = state.fragmentTruncatedHint
        if (!hint.isNullOrEmpty()) {
            withStyle(SpanStyle(color = colors.mutedForeground)) {
                append(hint)
            }
        }
    }

@Composable
private fun LegendBlock(state: RipDpiLinkPreviewState) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val shapes = RipDpiThemeTokens.shapes
    val type = RipDpiThemeTokens.type
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = state.eyebrowPayload.uppercase(Locale.getDefault()),
            style = type.sectionTitle,
            color = colors.mutedForeground,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shapes.lg,
            color = colors.background,
            border = BorderStroke(RipDpiStroke.Thin, colors.cardBorder),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                state.rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            thickness = RipDpiStroke.Thin,
                            color = colors.divider,
                        )
                    }
                    LegendRow(row = row)
                }
            }
        }
    }
}

@Composable
private fun LegendRow(row: RipDpiLinkPreviewRow) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            modifier = Modifier.weight(LegendKeyWeight),
            text = row.key,
            style = type.monoValue,
            color = colors.foreground,
        )
        Text(
            modifier = Modifier.weight(LegendValueWeight),
            text = row.value,
            style = type.body,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun PrivacyNote(state: RipDpiLinkPreviewState) {
    // privacyNoteEmphasis is carried on the state for callers that may
    // render their own banner; WarningBanner takes a plain String and
    // intentionally does not surface inline emphasis spans.
    WarningBanner(
        title = state.privacyNoteTitle,
        message = state.privacyNoteMessage,
        tone = WarningBannerTone.Info,
    )
}

private val PreviewSampleState =
    RipDpiLinkPreviewState(
        schemeLabel = "https://",
        hostLabel = "po4yka.github.io/RIPDPI/share",
        queryLabel = "?v=1",
        fragmentLabel = "eJxkkE2OwjAMha9SeY1m",
        fragmentTruncatedHint = "...",
        eyebrowLink = "Generated link",
        eyebrowPayload = "Fragment payload",
        copyLabel = "Copy",
        rows =
            persistentListOf(
                RipDpiLinkPreviewRow("v", AnnotatedString("Schema version - currently 1")),
                RipDpiLinkPreviewRow("asn", AnnotatedString("Origin ASN - AS13335")),
                RipDpiLinkPreviewRow("ts", AnnotatedString("Timestamp, in minutes since 2024-01-01 UTC")),
                RipDpiLinkPreviewRow("commit", AnnotatedString("Strategy-bundle hash - 0a")),
                RipDpiLinkPreviewRow("items[]", AnnotatedString("Per-endpoint {alive, dpi} tuples - 4")),
            ),
        privacyNoteTitle = "Stays on device",
        privacyNoteMessage =
            "The fragment never leaves the device - it's decoded locally by the recipient. " +
                "Hostnames are hashed unless redaction is off.",
        privacyNoteEmphasis = "never leaves the device",
    )

@Preview(showBackground = true, name = "RipDpiLinkPreviewCard (light)")
@Composable
private fun RipDpiLinkPreviewCardLightPreview() {
    RipDpiComponentPreview {
        RipDpiLinkPreviewCard(state = PreviewSampleState)
    }
}

@Preview(showBackground = true, name = "RipDpiLinkPreviewCard (dark)")
@Composable
private fun RipDpiLinkPreviewCardDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiLinkPreviewCard(state = PreviewSampleState)
    }
}
