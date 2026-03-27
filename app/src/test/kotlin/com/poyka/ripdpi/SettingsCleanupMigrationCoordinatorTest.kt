package com.poyka.ripdpi

import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.data.CurrentSettingsMigrationLevel
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.canonicalDefaultTcpChainSteps
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicy
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicySource
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCleanupMigrationCoordinatorTest {
    @Test
    fun `migrateIfNeeded rewrites legacy defaults and clears remembered state once`() =
        runTest {
            val repository = FakeAppSettingsRepository(legacyDefaultDisorderSettings())
            val rememberedPolicySource = CountingRememberedPolicySource()
            val coordinator = SettingsCleanupMigrationCoordinator(repository, rememberedPolicySource)

            coordinator.migrateIfNeeded()

            val migrated = repository.snapshot()
            assertEquals(CurrentSettingsMigrationLevel, migrated.settingsMigrationLevel)
            assertEquals(canonicalDefaultTcpChainSteps(), migrated.effectiveTcpChainSteps())
            assertEquals(1, rememberedPolicySource.clearCalls)

            coordinator.migrateIfNeeded()

            assertEquals(1, rememberedPolicySource.clearCalls)
        }

    private class CountingRememberedPolicySource : DiagnosticsRememberedPolicySource {
        var clearCalls = 0

        override fun observePolicies(limit: Int): Flow<List<DiagnosticsRememberedPolicy>> = emptyFlow()

        override suspend fun clearAll() {
            clearCalls += 1
        }
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
