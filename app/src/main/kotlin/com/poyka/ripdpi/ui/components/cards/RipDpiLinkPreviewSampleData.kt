@file:Suppress("detekt.MagicNumber")

package com.poyka.ripdpi.ui.components.cards

import androidx.compose.ui.text.AnnotatedString
import com.poyka.ripdpi.diagnostics.dpich.AliveState
import com.poyka.ripdpi.diagnostics.dpich.DpiState
import com.poyka.ripdpi.diagnostics.dpich.ShareLinkItem
import com.poyka.ripdpi.diagnostics.dpich.ShareLinkPayload
import kotlinx.collections.immutable.persistentListOf
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Localized string bundle consumed by [linkPreviewStateFrom]. Splitting
 * it out keeps the component string-free and the formatter call site
 * compact for screenshot / preview / production callers alike.
 */
data class RipDpiLinkPreviewStrings(
    val eyebrowLink: String,
    val eyebrowPayload: String,
    val copyLabel: String,
    val rowVersion: String,
    val rowAsnFormat: String,
    val rowAsnRedacted: String,
    val rowTimestampFormat: String,
    val rowCommitFormat: String,
    val rowItemsFormat: String,
    val privacyTitle: String,
    val privacyMessage: String,
    val privacyEmphasis: String,
)

private const val ShareLinkSchemePrefix = "https://"
private const val ShareLinkHostPath = "po4yka.github.io/RIPDPI/share"
private const val ShareLinkQuery = "?v=1"
private const val ShareEpochLiteral = "2024-01-01 UTC"
private const val SchemaVersionLiteral = "1"
private const val CommitHashRadix = 16
private const val CommitHashMinWidth = 2
private const val CommitHashPadChar = '0'
private const val SecondsPerMinute = 60L
private val ShareEpoch: Instant = Instant.parse("2024-01-01T00:00:00Z")
private val ShareTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.US)

private fun formatCommitHash(value: Int): String =
    value
        .toString(CommitHashRadix)
        .padStart(CommitHashMinWidth, CommitHashPadChar)

private fun formatTimestamp(timestampMinutes: Int): String =
    ShareTimestampFormatter.format(
        ShareEpoch
            .plusSeconds(timestampMinutes.toLong() * SecondsPerMinute)
            .atZone(ZoneOffset.UTC),
    )

private fun formatAsn(
    asn: Int,
    asnFormat: String,
    asnRedacted: String,
): String = if (asn == 0) asnRedacted else asnFormat.format("AS$asn")

/**
 * Builds a [RipDpiLinkPreviewState] from a real [ShareLinkPayload] and
 * the fully-formed fragment string. Production callers pass the same
 * fragment they fed to `DiagnosticShareLinkUrl.build`; the host /
 * scheme / query are derived from the canonical RIPDPI share URL so the
 * card matches what the share sheet copies.
 *
 * The optional [fragmentTruncatedHint] is appended to the fragment span
 * in muted-foreground; pass `"..."` when the visual frame is showing
 * only a prefix of a longer fragment.
 */
fun linkPreviewStateFrom(
    payload: ShareLinkPayload,
    fragment: String,
    strings: RipDpiLinkPreviewStrings,
    fragmentTruncatedHint: String? = null,
): RipDpiLinkPreviewState =
    RipDpiLinkPreviewState(
        schemeLabel = ShareLinkSchemePrefix,
        hostLabel = ShareLinkHostPath,
        queryLabel = ShareLinkQuery,
        fragmentLabel = fragment,
        fragmentTruncatedHint = fragmentTruncatedHint,
        eyebrowLink = strings.eyebrowLink,
        eyebrowPayload = strings.eyebrowPayload,
        copyLabel = strings.copyLabel,
        rows =
            persistentListOf(
                RipDpiLinkPreviewRow(
                    key = "v",
                    value = AnnotatedString(strings.rowVersion.format(SchemaVersionLiteral)),
                ),
                RipDpiLinkPreviewRow(
                    key = "asn",
                    value =
                        AnnotatedString(
                            formatAsn(
                                asn = payload.asn,
                                asnFormat = strings.rowAsnFormat,
                                asnRedacted = strings.rowAsnRedacted,
                            ),
                        ),
                ),
                RipDpiLinkPreviewRow(
                    key = "ts",
                    value =
                        AnnotatedString(
                            strings.rowTimestampFormat.format(ShareEpochLiteral) +
                                " (" + formatTimestamp(payload.timestampMinutes) + ")",
                        ),
                ),
                RipDpiLinkPreviewRow(
                    key = "commit",
                    value =
                        AnnotatedString(
                            strings.rowCommitFormat.format(formatCommitHash(payload.commitHash)),
                        ),
                ),
                RipDpiLinkPreviewRow(
                    key = "items[]",
                    value = AnnotatedString(strings.rowItemsFormat.format(payload.items.size)),
                ),
            ),
        privacyNoteTitle = strings.privacyTitle,
        privacyNoteMessage = strings.privacyMessage,
        privacyNoteEmphasis = strings.privacyEmphasis.takeIf { it.isNotEmpty() },
    )

/**
 * Demo payload + state for the screenshot test and the
 * `@Preview` composables. Numbers correspond to the values in
 * `docs/design/rds/preview/share-link-preview.html` so the rendered
 * card matches the spec card.
 */
fun sampleLinkPreviewState(strings: RipDpiLinkPreviewStrings): RipDpiLinkPreviewState =
    linkPreviewStateFrom(
        payload = sampleSharePayload(),
        fragment = "eJxkkE2OwjAMha9SeY1m",
        strings = strings,
        fragmentTruncatedHint = "...",
    )

fun sampleSharePayload(): ShareLinkPayload =
    ShareLinkPayload(
        commitHash = 0x0a,
        timestampMinutes = 1_217_280, // 2026-04-19 12:00 UTC
        asn = 13335,
        items =
            listOf(
                ShareLinkItem(alive = AliveState.YES, dpi = DpiState.NOT_DETECTED),
                ShareLinkItem(alive = AliveState.YES, dpi = DpiState.DETECTED),
                ShareLinkItem(alive = AliveState.UNKNOWN, dpi = DpiState.POSSIBLE),
                ShareLinkItem(alive = AliveState.NO, dpi = DpiState.UNLIKELY),
            ),
    )
