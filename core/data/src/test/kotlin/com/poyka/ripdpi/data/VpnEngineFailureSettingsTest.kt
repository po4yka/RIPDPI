package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BackupSettingsConverter
import com.poyka.ripdpi.data.backup.BackupVariant
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.VpnEngineFailureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnEngineFailureSettingsTest {
    @Test
    fun `unset and unknown values block traffic`() {
        assertTrue(AppSettingsSerializer.defaultValue.blocksTrafficOnEngineFailure())
        assertTrue(AppSettings.getDefaultInstance().blocksTrafficOnEngineFailure())
        val unknown = AppSettings.newBuilder().setVpnEngineFailureActionValue(7).build()
        assertTrue(AppSettings.parseFrom(unknown.toByteArray()).blocksTrafficOnEngineFailure())
    }

    @Test
    fun `setter writes the matching proto value`() {
        val stop = AppSettings.newBuilder().setBlocksTrafficOnEngineFailure(false).build()
        assertEquals(VpnEngineFailureAction.VPN_ENGINE_FAILURE_ACTION_STOP, stop.vpnEngineFailureAction)
        assertFalse(stop.blocksTrafficOnEngineFailure())

        val block = stop.toBuilder().setBlocksTrafficOnEngineFailure(true).build()
        assertEquals(VpnEngineFailureAction.VPN_ENGINE_FAILURE_ACTION_BLOCK, block.vpnEngineFailureAction)
    }

    @Test
    fun `FULL backup restores STOP and a legacy backup restores BLOCK`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setBlocksTrafficOnEngineFailure(false)
                .build()
        val backup = BackupSettingsConverter.toMap(settings, BackupVariant.FULL)
        assertFalse(requireNotNull(BackupSettingsConverter.fromMap(backup)).blocksTrafficOnEngineFailure())

        val legacy = mapOf(BackupSettingsConverter.LegacySnapshotKey to """{"appTheme":"dark"}""")
        assertTrue(requireNotNull(BackupSettingsConverter.fromMap(legacy)).blocksTrafficOnEngineFailure())
    }
}
