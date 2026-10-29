package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BackupSettingsConverter
import com.poyka.ripdpi.data.backup.BackupVariant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticFeedbackBackupTest {
    @Test
    fun `FULL backup restores disabled haptic feedback`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setHapticFeedbackDisabled(true)
                .build()

        val backup = BackupSettingsConverter.toMap(settings, BackupVariant.FULL)
        val restored = requireNotNull(BackupSettingsConverter.fromMap(backup))

        assertTrue(restored.hapticFeedbackDisabled)
    }

    @Test
    fun `FULL backup restores dynamic color and legacy backup defaults it off`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDynamicColorEnabled(true)
                .build()
        val backup = BackupSettingsConverter.toMap(settings, BackupVariant.FULL)
        assertTrue(requireNotNull(BackupSettingsConverter.fromMap(backup)).dynamicColorEnabled)

        val legacy = mapOf(BackupSettingsConverter.SnapshotKey to """{"appTheme":"dark"}""")
        assertFalse(requireNotNull(BackupSettingsConverter.fromMap(legacy)).dynamicColorEnabled)
    }

    @Test
    fun `legacy FULL backup retains system haptic feedback`() {
        val backup = mapOf(BackupSettingsConverter.SnapshotKey to """{"appTheme":"dark"}""")

        val restored = requireNotNull(BackupSettingsConverter.fromMap(backup))

        assertFalse(restored.hapticFeedbackDisabled)
    }

    @Test
    fun `SHARE backup omits settings when haptic feedback is disabled`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setHapticFeedbackDisabled(true)
                .build()

        val backup = BackupSettingsConverter.toMap(settings, BackupVariant.SHARE)

        assertTrue(backup.isEmpty())
        assertNull(BackupSettingsConverter.fromMap(backup))
    }
}
