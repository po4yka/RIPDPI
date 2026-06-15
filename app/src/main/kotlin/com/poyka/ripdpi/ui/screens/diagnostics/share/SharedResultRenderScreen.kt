package com.poyka.ripdpi.ui.screens.diagnostics.share

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.dpich.AliveState
import com.poyka.ripdpi.diagnostics.dpich.DiagnosticShareLinkCodec
import com.poyka.ripdpi.diagnostics.dpich.DiagnosticShareLinkMaxFragmentChars
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
private const val ShareLinkVersion = "1"
private const val CommitHashRadix = 16
private const val SecondsPerMinute = 60L
private const val CommitHashMinWidth = 2
private const val CommitHashPadChar = '0'
private val ShareEpoch = Instant.parse("2024-01-01T00:00:00Z")
private val ShareTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.US)
private val ShareFragmentPattern = Regex("[A-Za-z0-9_-]+")

internal object DiagnosticShareLinkDeepLink {
    fun fragmentFrom(intent: Intent?): String? {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        val matchesShareLink =
            uri?.scheme == ShareLinkScheme &&
                uri.host == ShareLinkHost &&
                uri.path == ShareLinkPath &&
                uri.getQueryParameter("v") == ShareLinkVersion
        return uri
            ?.encodedFragment
            ?.takeIf { matchesShareLink }
            ?.takeIf(String::isValidShareFragment)
    }
}

private fun String.isValidShareFragment(): Boolean =
    isNotBlank() && length <= DiagnosticShareLinkMaxFragmentChars && ShareFragmentPattern.matches(this)

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
            ShareEpoch.plusSeconds(payload.timestampMinutes.toLong() * SecondsPerMinute).atZone(ZoneOffset.UTC),
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
        title = stringResource(R.string.shared_diagnostic_screen_title),
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
                title = stringResource(R.string.shared_diagnostic_result_snapshot_title),
                supporting = SharedResultRenderFormatter.snapshotBanner(payload),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        stringResource(
                            R.string.shared_diagnostic_bundle_label,
                            payload.commitHash
                                .toString(CommitHashRadix)
                                .padStart(CommitHashMinWidth, CommitHashPadChar),
                        ),
                    style = RipDpiThemeTokens.type.smallLabel,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                Text(
                    text = stringResource(R.string.shared_diagnostic_endpoints_count, payload.items.size),
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
                text = stringResource(R.string.shared_diagnostic_endpoint_label, index + 1),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            Text(
                text = stringResource(R.string.shared_diagnostic_alive_prefix, item.alive.displayLabel()),
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
            title = stringResource(R.string.shared_diagnostic_open_error_title),
            supporting =
                when (error) {
                    is ShareLinkDecodeError -> error.message.orEmpty()
                    else -> stringResource(R.string.shared_diagnostic_invalid_link_message)
                },
        )
        Text(
            text = stringResource(R.string.shared_diagnostic_ask_fresh_link),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

@Composable
private fun AliveState.displayLabel(): String =
    stringResource(
        when (this) {
            AliveState.NO -> R.string.shared_diagnostic_alive_no
            AliveState.YES -> R.string.shared_diagnostic_alive_yes
            AliveState.UNKNOWN -> R.string.diagnostics_field_unknown
        },
    )

@Composable
private fun DpiState.displayLabel(): String =
    stringResource(
        when (this) {
            DpiState.NOT_DETECTED -> R.string.shared_diagnostic_dpi_not_detected
            DpiState.DETECTED -> R.string.detection_check_verdict_detected
            DpiState.PROBABLY -> R.string.shared_diagnostic_dpi_probably
            DpiState.POSSIBLE -> R.string.shared_diagnostic_dpi_possible
            DpiState.UNLIKELY -> R.string.shared_diagnostic_dpi_unlikely
        },
    )

private fun DpiState.statusTone(): StatusIndicatorTone =
    when (this) {
        DpiState.DETECTED -> StatusIndicatorTone.Error

        DpiState.PROBABLY,
        DpiState.POSSIBLE,
        -> StatusIndicatorTone.Warning

        DpiState.UNLIKELY -> StatusIndicatorTone.Idle

        DpiState.NOT_DETECTED -> StatusIndicatorTone.Active
    }
