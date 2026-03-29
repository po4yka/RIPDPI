package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeAdaptive
import com.poyka.ripdpi.activities.DesyncCoreUiState
import com.poyka.ripdpi.activities.FakeTransportUiState
import com.poyka.ripdpi.activities.SettingsEffect
import com.poyka.ripdpi.activities.SettingsMutation
import com.poyka.ripdpi.activities.SettingsNoticeTone
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.CanonicalDefaultSplitMarker
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedSettingsRouteBinderTest {
    // -- mapNoticeEffect --

    @Test
    fun `mapNoticeEffect maps Info tone`() {
        val effect =
            SettingsEffect.Notice(
                title = "Info title",
                message = "Info body",
                tone = SettingsNoticeTone.Info,
            )

        val result = mapNoticeEffect(effect)

        assertEquals("Info title", result.title)
        assertEquals("Info body", result.message)
        assertEquals(WarningBannerTone.Info, result.tone)
    }

    @Test
    fun `mapNoticeEffect maps Warning tone`() {
        val effect =
            SettingsEffect.Notice(
                title = "Warn",
                message = "msg",
                tone = SettingsNoticeTone.Warning,
            )

        assertEquals(WarningBannerTone.Warning, mapNoticeEffect(effect).tone)
    }

    @Test
    fun `mapNoticeEffect maps Error tone`() {
        val effect =
            SettingsEffect.Notice(
                title = "Err",
                message = "msg",
                tone = SettingsNoticeTone.Error,
            )

        assertEquals(WarningBannerTone.Error, mapNoticeEffect(effect).tone)
    }

    // -- parseOptionalRangeValue --

    @Test
    fun `parseOptionalRangeValue parses valid long`() {
        assertEquals(42L, parseOptionalRangeValue("42"))
    }

    @Test
    fun `parseOptionalRangeValue trims whitespace`() {
        assertEquals(7L, parseOptionalRangeValue("  7  "))
    }

    @Test
    fun `parseOptionalRangeValue returns null for empty string`() {
        assertNull(parseOptionalRangeValue(""))
    }

    @Test
    fun `parseOptionalRangeValue returns null for whitespace-only`() {
        assertNull(parseOptionalRangeValue("   "))
    }

    @Test
    fun `parseOptionalRangeValue returns null for non-numeric`() {
        assertNull(parseOptionalRangeValue("abc"))
    }

    // -- manualSplitMarkerFallback --

    @Test
    fun `manualSplitMarkerFallback returns marker when not adaptive`() {
        val uiState = SettingsUiState(desync = DesyncCoreUiState(splitMarker = "2"))

        assertEquals("2", manualSplitMarkerFallback(uiState))
    }

    @Test
    fun `manualSplitMarkerFallback falls back to default for adaptive marker`() {
        val uiState = SettingsUiState(desync = DesyncCoreUiState(splitMarker = "auto(balanced)"))

        assertEquals(CanonicalDefaultSplitMarker, manualSplitMarkerFallback(uiState))
    }

    @Test
    fun `binder toggles diagnostics monitor`() {
        val recorder = RecordingSettingsMutations()
        val binder = AdvancedSettingsBinder(recorder::updateSetting)

        binder.onToggleChanged(AdvancedToggleSetting.DiagnosticsMonitorEnabled, enabled = false)

        val update = recorder.singleUpdate()
        assertEquals("diagnosticsMonitorEnabled", update.key)
        assertEquals("false", update.value)
        assertFalse(update.settings.diagnosticsMonitorEnabled)
    }

    @Test
    fun `binder ignores invalid numeric text input`() {
        val recorder = RecordingSettingsMutations()
        val binder = AdvancedSettingsBinder(recorder::updateSetting)

        binder.onTextConfirmed(
            setting = AdvancedTextSetting.ProxyPort,
            value = "abc",
            uiState = SettingsUiState(),
        )

        assertTrue(recorder.updates.isEmpty())
    }

    @Test
    fun `binder clears custom ttl when default ttl input is blank`() {
        val recorder = RecordingSettingsMutations()
        val binder = AdvancedSettingsBinder(recorder::updateSetting)

        binder.onTextConfirmed(
            setting = AdvancedTextSetting.DefaultTtl,
            value = "",
            uiState = SettingsUiState(),
        )

        val update = recorder.singleUpdate()
        assertEquals("defaultTtl", update.key)
        assertFalse(update.settings.customTtl)
        assertEquals(0, update.settings.defaultTtl)
    }

    @Test
    fun `binder normalizes quic fake host input`() {
        val recorder = RecordingSettingsMutations()
        val binder = AdvancedSettingsBinder(recorder::updateSetting)

        binder.onTextConfirmed(
            setting = AdvancedTextSetting.QuicFakeHost,
            value = " Video.Example.Test ",
            uiState = SettingsUiState(),
        )

        val update = recorder.singleUpdate()
        assertEquals("quicFakeHost", update.key)
        assertEquals("video.example.test", update.value)
        assertEquals("video.example.test", update.settings.quicFakeHost)
    }

    @Test
    fun `binder switches adaptive fake ttl mode to adaptive using current bounds`() {
        val recorder = RecordingSettingsMutations()
        val binder = AdvancedSettingsBinder(recorder::updateSetting)
        val uiState =
            SettingsUiState(
                fake =
                    FakeTransportUiState(
                        fakeTtl = 17,
                        adaptiveFakeTtlMin = 4,
                        adaptiveFakeTtlMax = 19,
                    ),
            )

        binder.onOptionSelected(
            setting = AdvancedOptionSetting.AdaptiveFakeTtlMode,
            value = AdaptiveFakeTtlModeAdaptive,
            uiState = uiState,
        )

        val update = recorder.singleUpdate()
        assertEquals("adaptiveFakeTtlEnabled", update.key)
        assertTrue(update.settings.adaptiveFakeTtlEnabled)
        assertEquals(-1, update.settings.adaptiveFakeTtlDelta)
        assertEquals(4, update.settings.adaptiveFakeTtlMin)
        assertEquals(19, update.settings.adaptiveFakeTtlMax)
        assertEquals(17, update.settings.adaptiveFakeTtlFallback)
    }

    @Test
    fun `binder preserves saved seqovl when desync method is replayed`() {
        val recorder = RecordingSettingsMutations()
        val binder = AdvancedSettingsBinder(recorder::updateSetting)
        val uiState =
            SettingsUiState(
                desync =
                    DesyncCoreUiState(
                        desyncMethod = TcpChainStepKind.SeqOverlap.wireName,
                        tcpChainSteps =
                            listOf(
                                TcpChainStepModel(TcpChainStepKind.TlsRec, "extlen"),
                                TcpChainStepModel(
                                    kind = TcpChainStepKind.SeqOverlap,
                                    marker = "midsld",
                                    overlapSize = 16,
                                    fakeMode = "profile",
                                ),
                            ),
                        splitMarker = "midsld",
                    ),
            )

        binder.onOptionSelected(
            setting = AdvancedOptionSetting.DesyncMethod,
            value = TcpChainStepKind.SeqOverlap.wireName,
            uiState = uiState,
        )

        val update = recorder.singleUpdate()
        assertEquals("desyncMethod", update.key)
        assertEquals(TcpChainStepKind.SeqOverlap.wireName, update.value)
        assertEquals("seqovl", update.settings.tcpChainStepsList[1].kind)
        assertEquals("midsld", update.settings.tcpChainStepsList[1].marker)
        assertEquals(16, update.settings.tcpChainStepsList[1].overlapSize)
        assertEquals("profile", update.settings.tcpChainStepsList[1].fakeMode)
    }

    @Test
    fun `binder saves normalized activation window range`() {
        val recorder = RecordingSettingsMutations()
        val binder = AdvancedSettingsBinder(recorder::updateSetting)

        binder.onSaveActivationRange(
            dimension = ActivationWindowDimension.Round,
            start = 10L,
            end = 5L,
            uiState = SettingsUiState(),
        )

        val update = recorder.singleUpdate()
        assertEquals("groupActivationFilter.round", update.key)
        assertTrue(update.settings.hasGroupActivationFilter())
        assertEquals(5L, update.settings.groupActivationFilter.round.start)
        assertEquals(10L, update.settings.groupActivationFilter.round.end)
    }

    private class RecordingSettingsMutations {
        data class RecordedUpdate(
            val key: String,
            val value: String,
            val settings: AppSettings,
        )

        val updates = mutableListOf<RecordedUpdate>()

        fun updateSetting(
            key: String,
            value: String,
            transform: SettingsMutation,
        ) {
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .apply(transform)
                    .build()
            updates += RecordedUpdate(key = key, value = value, settings = settings)
        }

        fun singleUpdate(): RecordedUpdate {
            assertEquals(1, updates.size)
            return updates.single()
        }
    }
}
