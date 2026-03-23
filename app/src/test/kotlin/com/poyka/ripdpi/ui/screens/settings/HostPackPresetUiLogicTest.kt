package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.HostPackCatalogUiState
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.HostPackCatalog
import com.poyka.ripdpi.data.HostPackCatalogSnapshot
import com.poyka.ripdpi.data.HostPackCatalogSourceDownloaded
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.data.HostPackSource
import com.poyka.ripdpi.data.HostPackTargetBlacklist
import com.poyka.ripdpi.data.HostPackTargetWhitelist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class HostPackPresetUiLogicTest {
    @Test
    fun `default target follows current hosts mode`() {
        assertEquals(
            HostPackTargetWhitelist,
            defaultHostPackTargetMode(SettingsUiState(hostsMode = HostPackTargetWhitelist)),
        )
        assertEquals(
            HostPackTargetBlacklist,
            defaultHostPackTargetMode(SettingsUiState(hostsMode = "disable")),
        )
    }

    @Test
    fun `apply is disabled while command line overrides are active`() {
        assertFalse(hostPackApplyEnabled(SettingsUiState(enableCmdSettings = true)))
        assertTrue(hostPackApplyEnabled(SettingsUiState(enableCmdSettings = false)))
    }

    @Test
    fun `refresh stays available while command line overrides are active`() {
        assertTrue(hostPackRefreshEnabled(HostPackCatalogUiState(isRefreshing = false)))
        assertFalse(hostPackRefreshEnabled(HostPackCatalogUiState(isRefreshing = true)))
    }

    @Test
    fun `source summary prefers short commit shas`() {
        val preset =
            HostPackPreset(
                id = "youtube",
                title = "YouTube",
                description = "Video hosts",
                hostCount = 2,
                hosts = listOf("youtube.com", "ytimg.com"),
                sources =
                    listOf(
                        HostPackSource(
                            name = "runetfreedom/russia-blocked-geosite",
                            url = "https://example.test/youtube.txt",
                            ref = "release",
                            commit = "abcdef1234567890",
                        ),
                    ),
            )

        assertEquals(
            "runetfreedom/russia-blocked-geosite @ abcdef1",
            hostPackSourceSummary(preset),
        )
    }

    @Test
    fun `formatting helpers render local timestamps`() {
        val previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tbilisi"))

        try {
            assertEquals("Mar 12, 13:00", formatHostPackGeneratedAt("2026-03-12T09:00:00Z"))
            assertEquals("Mar 12, 13:00", formatHostPackFetchedAt(1_773_306_000_000))
        } finally {
            TimeZone.setDefault(previousTimeZone)
        }
    }

    @Test
    fun `downloaded catalog state exposes presets from snapshot`() {
        val state =
            HostPackCatalogUiState(
                snapshot =
                    HostPackCatalogSnapshot(
                        catalog =
                            HostPackCatalog(
                                packs =
                                    listOf(
                                        HostPackPreset(
                                            id = "youtube",
                                            title = "YouTube",
                                            description = "Video hosts",
                                            hostCount = 1,
                                            hosts = listOf("youtube.com"),
                                        ),
                                    ),
                            ),
                        source = HostPackCatalogSourceDownloaded,
                    ),
            )

        assertEquals(listOf("youtube"), state.presets.map { it.id })
    }
}
