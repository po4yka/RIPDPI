package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCleanupMigrationTest {
    @Test
    fun `legacy default disorder settings migrate to explicit canonical split`() {
        val migrated = legacyDefaultDisorderSettings().applySettingsCleanupMigration()

        assertEquals(CurrentSettingsMigrationLevel, migrated.settingsMigrationLevel)
        assertEquals(canonicalDefaultTcpChainSteps(), migrated.effectiveTcpChainSteps())
        assertEquals(1, migrated.tcpChainStepsCount)
        assertEquals("split", migrated.desyncMethod)
        assertEquals(CanonicalDefaultSplitMarker, migrated.splitMarker)
    }

    @Test
    fun `custom legacy disorder settings are preserved while being canonicalized`() {
        val migrated =
            legacyDefaultDisorderSettings()
                .toBuilder()
                .setSplitMarker("host+2")
                .setDefaultTtl(32)
                .setCustomTtl(true)
                .build()
                .applySettingsCleanupMigration()

        assertEquals(CurrentSettingsMigrationLevel, migrated.settingsMigrationLevel)
        assertEquals(listOf(TcpChainStepModel(TcpChainStepKind.Disorder, "host+2")), migrated.effectiveTcpChainSteps())
        assertEquals(1, migrated.tcpChainStepsCount)
        assertEquals("disorder", migrated.desyncMethod)
        assertEquals("host+2", migrated.splitMarker)
    }

    @Test
    fun `migration is idempotent once level is recorded`() {
        val migratedOnce = legacyDefaultDisorderSettings().applySettingsCleanupMigration()
        val migratedTwice = migratedOnce.applySettingsCleanupMigration()

        assertEquals(migratedOnce, migratedTwice)
    }

    @Test
    fun `command mode only bumps migration level without rewriting args or chains`() {
        val migrated =
            legacyDefaultDisorderSettings()
                .toBuilder()
                .setEnableCmdSettings(true)
                .setCmdArgs("--split 5 --ttl 8")
                .build()
                .applySettingsCleanupMigration()

        assertEquals(CurrentSettingsMigrationLevel, migrated.settingsMigrationLevel)
        assertEquals("--split 5 --ttl 8", migrated.cmdArgs)
        assertTrue(migrated.enableCmdSettings)
        assertEquals(0, migrated.tcpChainStepsCount)
    }

    private fun legacyDefaultDisorderSettings(): AppSettings =
        AppSettings
            .newBuilder()
            .setDesyncMethod("disorder")
            .setSplitPosition(1)
            .setSplitMarker(DefaultSplitMarker)
            .setDesyncHttp(true)
            .setDesyncHttps(true)
            .build()
}
