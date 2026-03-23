package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.HostPackCatalog
import com.poyka.ripdpi.data.HostPackCatalogSnapshot
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.data.HostPackTargetBlacklist
import com.poyka.ripdpi.data.HostPackTargetWhitelist
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun defaultHostPackTargetMode(uiState: SettingsUiState): String =
    if (uiState.hostsMode == HostPackTargetWhitelist) {
        HostPackTargetWhitelist
    } else {
        HostPackTargetBlacklist
    }

internal fun hostPackApplyEnabled(uiState: SettingsUiState): Boolean = !uiState.enableCmdSettings

internal fun hostPackRefreshEnabled(hostPackCatalog: HostPackCatalogUiState): Boolean = !hostPackCatalog.isRefreshing

internal fun hostPackSourceSummary(preset: HostPackPreset): String =
    preset.sources.joinToString(separator = " · ") { source ->
        buildString {
            append(source.name)
            append(" @ ")
            append(source.commit?.take(7) ?: source.ref)
        }
    }

private val hostPackTimestampFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)

internal fun formatHostPackGeneratedAt(timestamp: String): String? =
    runCatching {
        hostPackTimestampFormatter.format(Instant.parse(timestamp).atZone(ZoneId.systemDefault()))
    }.getOrNull()

internal fun formatHostPackFetchedAt(timestampMillis: Long): String =
    hostPackTimestampFormatter.format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()))

internal fun previewHostPackPresets(): List<HostPackPreset> =
    listOf(
        HostPackPreset(
            id = "youtube",
            title = "YouTube",
            description = "Video playback, embeds, and CDN endpoints from public geosite lists.",
            hostCount = 178,
            hosts = listOf("youtube.com", "ytimg.com"),
        ),
        HostPackPreset(
            id = "telegram",
            title = "Telegram",
            description = "Messenger, CDN, and share links bundled from public geosite sources.",
            hostCount = 20,
            hosts = listOf("telegram.org", "t.me"),
        ),
        HostPackPreset(
            id = "discord",
            title = "Discord",
            description = "App, media, invite, and attachment domains from public geosite lists.",
            hostCount = 28,
            hosts = listOf("discord.com", "discord.gg"),
        ),
    )

internal fun previewHostPackCatalog(
    source: String,
    lastFetchedAtEpochMillis: Long? = null,
): HostPackCatalogUiState =
    HostPackCatalogUiState(
        snapshot =
            HostPackCatalogSnapshot(
                catalog =
                    HostPackCatalog(
                        generatedAt = "2026-03-12T09:00:00Z",
                        packs = previewHostPackPresets(),
                    ),
                source = source,
                lastFetchedAtEpochMillis = lastFetchedAtEpochMillis,
                verifiedChecksumSha256 =
                    lastFetchedAtEpochMillis?.let {
                        "96b19c3ec2011e4e5ec87dd54b3c209f1e0efaa36fe8b5dd275129b032a01438"
                    },
            ),
    )
