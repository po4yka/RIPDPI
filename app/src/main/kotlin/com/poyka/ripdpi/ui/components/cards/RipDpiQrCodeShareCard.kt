package com.poyka.ripdpi.ui.components.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.util.Locale

private const val QrPlateSizeMultiplier = 4
private const val SpecsLabelWeight = 0.4f
private const val SpecsValueWeight = 0.6f
private const val DebugQrPlaceholderSize = 160

/**
 * @param captionEmphasis Optional substring of [caption] to render in
 * the foreground color (matches the `<b>no network traffic</b>`
 * highlight in `docs/design/rds/preview/share-qr-code.html`). Must be
 * a literal substring of [caption]; if not found, the caption renders
 * unstyled.
 */
data class RipDpiQrCodeMetadata(
    val eyebrow: String,
    val title: String,
    val versionLabel: String,
    val payloadLabel: String,
    val schemaLabel: String,
    val eccLabel: String,
    val caption: String,
    val captionEmphasis: String? = null,
)

/**
 * Styled wrapper around an offline-encoded QR [ImageBitmap]. Adds the
 * brand sidebar (version, payload size, schema, ECC level, no-network
 * caption) so consumers only need to encode the matrix and supply
 * deterministic metadata.
 *
 * Matches `docs/design/rds/preview/share-qr-code.html`.
 */
@Composable
fun RipDpiQrCodeShareCard(
    qrBitmap: ImageBitmap,
    metadata: RipDpiQrCodeMetadata,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val shapes = RipDpiThemeTokens.shapes
    val type = RipDpiThemeTokens.type
    val plateSize = spacing.section * QrPlateSizeMultiplier

    RipDpiCard(modifier = modifier, variant = RipDpiCardVariant.Outlined) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(plateSize),
                shape = shapes.md,
                color = colors.background,
                border = BorderStroke(RipDpiStroke.Thin, colors.border),
            ) {
                Box(
                    modifier = Modifier.padding(spacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = metadata.eyebrow.uppercase(Locale.getDefault()),
                    style = type.sectionTitle,
                    color = colors.mutedForeground,
                )
                Text(
                    text = metadata.title,
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                RipDpiQrCodeSpecs(metadata = metadata)
                Text(
                    text = buildCaption(metadata, colors.foreground),
                    style = type.caption,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}

@Composable
private fun RipDpiQrCodeSpecs(metadata: RipDpiQrCodeMetadata) {
    val spacing = RipDpiThemeTokens.spacing
    val pairs =
        listOf(
            stringResource(R.string.qr_share_spec_version) to metadata.versionLabel,
            stringResource(R.string.qr_share_spec_payload) to metadata.payloadLabel,
            stringResource(R.string.qr_share_spec_schema) to metadata.schemaLabel,
            stringResource(R.string.qr_share_spec_ecc) to metadata.eccLabel,
        )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        pairs.forEach { (label, value) ->
            RipDpiQrCodeSpecRow(label = label, value = value)
        }
    }
}

@Composable
private fun RipDpiQrCodeSpecRow(
    label: String,
    value: String,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(SpecsLabelWeight),
            text = label,
            style = type.smallLabel,
            color = colors.mutedForeground,
        )
        Text(
            modifier = Modifier.weight(SpecsValueWeight),
            text = value,
            style = type.monoSmall,
            color = colors.foreground,
        )
    }
}

private fun buildCaption(
    metadata: RipDpiQrCodeMetadata,
    emphasisColor: Color,
): AnnotatedString {
    val highlight = metadata.captionEmphasis
    if (highlight.isNullOrEmpty() || !metadata.caption.contains(highlight)) {
        return AnnotatedString(metadata.caption)
    }
    val start = metadata.caption.indexOf(highlight)
    return buildAnnotatedString {
        append(metadata.caption.substring(0, start))
        withStyle(SpanStyle(color = emphasisColor)) {
            append(highlight)
        }
        append(metadata.caption.substring(start + highlight.length))
    }
}

private fun debugQrPlaceholder(): ImageBitmap = ImageBitmap(DebugQrPlaceholderSize, DebugQrPlaceholderSize)

private val SampleMetadata =
    RipDpiQrCodeMetadata(
        eyebrow = "QR share · v3",
        title = "Bundle 0a · 4 endpoints",
        versionLabel = "QR-3 (29x29)",
        payloadLabel = "184 chars",
        schemaLabel = "v1",
        eccLabel = "M · 15%",
        caption = "Scan with another RIPDPI install to import this diagnostic — no network traffic.",
        captionEmphasis = "no network traffic",
    )

@Preview(showBackground = true, name = "RipDpiQrCodeShareCard (light)")
@Composable
private fun RipDpiQrCodeShareCardLightPreview() {
    val placeholder = remember { debugQrPlaceholder() }
    RipDpiComponentPreview {
        RipDpiQrCodeShareCard(
            qrBitmap = placeholder,
            metadata = SampleMetadata,
        )
    }
}

@Preview(showBackground = true, name = "RipDpiQrCodeShareCard (dark)")
@Composable
private fun RipDpiQrCodeShareCardDarkPreview() {
    val placeholder = remember { debugQrPlaceholder() }
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiQrCodeShareCard(
            qrBitmap = placeholder,
            metadata = SampleMetadata,
        )
    }
}
