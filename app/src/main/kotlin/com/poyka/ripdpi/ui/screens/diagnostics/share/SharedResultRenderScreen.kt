package com.poyka.ripdpi.ui.screens.diagnostics.share

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.poyka.ripdpi.diagnostics.dpich.AliveState
import com.poyka.ripdpi.diagnostics.dpich.DiagnosticShareLinkCodec
import com.poyka.ripdpi.diagnostics.dpich.DpiState
import com.poyka.ripdpi.diagnostics.dpich.ShareLinkDecodeError
import com.poyka.ripdpi.diagnostics.dpich.ShareLinkItem
import com.poyka.ripdpi.diagnostics.dpich.ShareLinkPayload
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ShareLinkScheme = "https"
private const val ShareLinkHost = "po4yka.github.io"
private const val ShareLinkPath = "/RIPDPI/share"
private val ShareEpoch = Instant.parse("2024-01-01T00:00:00Z")
private val ShareTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.US)

internal object DiagnosticShareLinkDeepLink {
    fun fragmentFrom(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) {
            return null
        }
        val uri = intent.data ?: return null
        if (uri.scheme != ShareLinkScheme || uri.host != ShareLinkHost || uri.path != ShareLinkPath) {
            return null
        }
        if (uri.getQueryParameter("v") != "1") {
            return null
        }
        return uri.fragment?.takeIf(String::isNotBlank)
    }
}

internal object DiagnosticShareLinkUrl {
    fun build(
        baseUrl: String,
        fragment: String,
    ): String = "${baseUrl.trimEnd('/')}?v=1#$fragment"
}

internal object SharedResultRenderFormatter {
    fun originLabel(payload: ShareLinkPayload): String =
        if (payload.asn == 0) {
            "Unknown origin"
        } else {
            "AS${payload.asn}"
        }

    fun snapshotBanner(payload: ShareLinkPayload): String {
        val origin = originLabel(payload)
        val timestamp = timestampLabel(payload)
        return "Shared diagnostic from $origin, $timestamp. This is a snapshot, not a live test."
    }

    fun timestampLabel(payload: ShareLinkPayload): String =
        ShareTimestampFormatter.format(
            ShareEpoch.plusSeconds(payload.timestampMinutes.toLong() * 60).atZone(ZoneOffset.UTC),
        )
}

@Composable
fun SharedResultRenderRoute(
    fragment: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val decoded =
        remember(fragment) {
            runCatching { DiagnosticShareLinkCodec.decode(fragment) }
        }
    SharedResultRenderScreen(
        decoded = decoded,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun SharedResultRenderScreen(
    decoded: Result<ShareLinkPayload>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RipDpiContentScreenScaffold(
        title = "Shared diagnostic",
        navigationIcon = RipDpiIcons.Back,
        onNavigationClick = onBack,
        modifier = modifier.ripDpiTestTag(RipDpiTestTags.screen(Route.SharedDiagnosticResult())),
    ) {
        decoded.fold(
            onSuccess = { payload -> SharedResultPayload(payload) },
            onFailure = { error -> SharedResultError(error) },
        )
    }
}

@Composable
private fun SharedResultPayload(payload: ShareLinkPayload) {
    val spacing = RipDpiThemeTokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        RipDpiCard {
            RipDpiPanelHeader(
                title = "Result snapshot",
                supporting = SharedResultRenderFormatter.snapshotBanner(payload),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Bundle ${payload.commitHash.toString(16).padStart(2, '0')}",
                    style = RipDpiThemeTokens.type.smallLabel,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                Text(
                    text = "${payload.items.size} endpoints",
                    style = RipDpiThemeTokens.type.smallLabel,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
        }
        RipDpiCard {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                payload.items.forEachIndexed { index, item ->
                    SharedResultRow(index = index, item = item)
                }
            }
        }
    }
}

@Composable
private fun SharedResultRow(
    index: Int,
    item: ShareLinkItem,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Endpoint ${index + 1}",
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            Text(
                text = "Alive: ${item.alive.displayLabel()}",
                style = RipDpiThemeTokens.type.smallLabel,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        StatusIndicator(
            label = item.dpi.displayLabel(),
            tone = item.dpi.statusTone(),
        )
    }
}

@Composable
private fun SharedResultError(error: Throwable) {
    RipDpiCard {
        RipDpiPanelHeader(
            title = "Cannot open shared diagnostic",
            supporting =
                when (error) {
                    is ShareLinkDecodeError -> error.message.orEmpty()
                    else -> "The shared diagnostic link is not valid."
                },
        )
        Text(
            text = "Ask the sender to share a fresh link.",
            style = MaterialTheme.typography.bodyMedium,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

private fun AliveState.displayLabel(): String =
    when (this) {
        AliveState.NO -> "No"
        AliveState.YES -> "Yes"
        AliveState.UNKNOWN -> "Unknown"
    }

private fun DpiState.displayLabel(): String =
    when (this) {
        DpiState.NOT_DETECTED -> "Not detected"
        DpiState.DETECTED -> "Detected"
        DpiState.PROBABLY -> "Probably"
        DpiState.POSSIBLE -> "Possible"
        DpiState.UNLIKELY -> "Unlikely"
    }

private fun DpiState.statusTone(): StatusIndicatorTone =
    when (this) {
        DpiState.DETECTED -> StatusIndicatorTone.Error

        DpiState.PROBABLY,
        DpiState.POSSIBLE,
        -> StatusIndicatorTone.Warning

        DpiState.UNLIKELY -> StatusIndicatorTone.Idle

        DpiState.NOT_DETECTED -> StatusIndicatorTone.Active
    }
