package com.poyka.ripdpi.ui.screens.connection

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayPresetCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

@RunWith(AndroidJUnit4::class)
class ConnectionRelayPresetTuicTest {
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
