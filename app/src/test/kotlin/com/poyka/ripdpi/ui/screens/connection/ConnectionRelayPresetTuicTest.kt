package com.poyka.ripdpi.ui.screens.connection

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayPresetCatalog
import com.poyka.ripdpi.data.applyRelayAfterImage
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import com.poyka.ripdpi.proxyimport.RelayProfileShareMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

@RunWith(AndroidJUnit4::class)
class ConnectionRelayPresetTuicTest {
    @Test
    fun `unrelated edit retains both native NewReno spellings from an imported URI`() {
        for (congestion in listOf("new_reno", "newreno")) {
            val imported =
                requireNotNull(
                    ProxyUriCodec.parse(
                        "tuic://11111111-2222-3333-4444-555555555555:fixture@h.example:443/" +
                            "?sni=tls.example&alpn=tuic%2Ch3&congestion_control=$congestion#Node",
                    ),
                )
            val mapped = requireNotNull(mapRelayProfile(imported))
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .apply { applyRelayAfterImage(mapped.profile, enabled = true) }
                    .build()
            val draft =
                settings
                    .toConfigDraft()
                    .withRelayArtifacts(mapped.profile, mapped.credentials)
                    .copy(relayServerPort = "9443")
            val saved = draft.toRelayProfileRecord(mapped.profile.id)
            val credentials = draft.toRelayCredentialRecord(mapped.profile.id)
            val savedSettings = settings.toBuilder().applyConfigDraft(draft).build()
            val share = requireNotNull(RelayProfileShareMapper.toShareable(saved, credentials))
            val reimported = ProxyUriCodec.parse(share.shareUri) as ProxyProfile.Tuic

            assertEquals(congestion, draft.relayTuicCongestionControl)
            assertEquals(congestion, saved.tuicCongestionControl)
            assertEquals(congestion, savedSettings.relayTuicCongestionControl)
            assertEquals(congestion, reimported.congestionControl)
            assertEquals(9443, reimported.serverPort)
            assertEquals("11111111-2222-3333-4444-555555555555", reimported.uuid)
            assertEquals("fixture", reimported.password)
        }
    }

    @Test
    fun `every bundled tuic preset saves without zero rtt`() {
        val tuicPresets =
            RelayPresetCatalog(RuntimeEnvironment.getApplication()).all().filter { it.relayKind == RelayKindTuicV5 }
        assertTrue(tuicPresets.isNotEmpty())
        tuicPresets.forEach { preset ->
            val profile = ConnectionDraft().applyRelayPresetDefinition(preset).toRelayProfileRecord("p")
            assertFalse(preset.id, profile.tuicZeroRtt)
        }
    }
}
