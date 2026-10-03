package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.AppSettingsSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnedRelayQuicMigrationConfigTest {
    @Test
    fun `settings projection matches canonical UI preferences for all QUIC flag combinations`() {
        for (bindLowPort in listOf(false, true)) {
            for (migrateAfterHandshake in listOf(false, true)) {
                val settings =
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setEnableCmdSettings(false)
                        .setQuicBindLowPort(bindLowPort)
                        .setQuicMigrateAfterHandshake(migrateAfterHandshake)
                        .build()
                assertEquals(
                    RipDpiProxyUIPreferences.fromSettings(settings).ownedRelayQuicMigrationConfig(),
                    settings.ownedRelayQuicMigrationConfig(),
                )
            }
        }
    }

    @Test
    fun `command mode ignores stored UI flags just like canonical command preferences`() {
        for (bindLowPort in listOf(false, true)) {
            for (migrateAfterHandshake in listOf(false, true)) {
                val settings =
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setEnableCmdSettings(true)
                        .setCmdArgs("--port 1081")
                        .setQuicBindLowPort(bindLowPort)
                        .setQuicMigrateAfterHandshake(migrateAfterHandshake)
                        .build()
                assertEquals(
                    RipDpiProxyCmdPreferences(settings.cmdArgs).ownedRelayQuicMigrationConfig(),
                    settings.ownedRelayQuicMigrationConfig(),
                )
                assertEquals(OwnedRelayQuicMigrationConfig(), settings.ownedRelayQuicMigrationConfig())
            }
        }
    }
}
