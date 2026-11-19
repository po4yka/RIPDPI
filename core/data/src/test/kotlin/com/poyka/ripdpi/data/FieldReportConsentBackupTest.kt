package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.backup.BackupSettingsConverter
import com.poyka.ripdpi.data.backup.BackupVariant
import org.junit.Assert.assertFalse
import org.junit.Test

class FieldReportConsentBackupTest {
    @Test
    fun `field report consent is off by default and a FULL backup never restores it`() {
        assertFalse(AppSettingsSerializer.defaultValue.fieldReportEnabled)

        val consented =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setFieldReportEnabled(true)
                .build()
        val backup = BackupSettingsConverter.toMap(consented, BackupVariant.FULL)

        assertFalse(requireNotNull(BackupSettingsConverter.fromMap(backup)).fieldReportEnabled)
    }
}
