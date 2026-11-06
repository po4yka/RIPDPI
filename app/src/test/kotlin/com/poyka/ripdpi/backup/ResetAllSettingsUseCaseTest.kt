package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.backup.BackupPrivateDataV1
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryResetStore
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResetAllSettingsUseCaseTest : BackupRoomTest() {
    private suspend fun seededFixture(): RoomProfileFixture =
        roomFixture().also { fixture ->
            fixture.coordinator.restoreBackup(
                fixture.coordinator.captureMutation(ProfileMutationOrigin.RestoreProfiles),
                listOf(
                    ProxyGroup(
                        id = "group",
                        name = "Group",
                        type = ProxyGroupType.BASIC,
                        order = 0,
                        isSelector = false,
                    ),
                ),
                listOf(RuleEntity(name = "rule", outboundTag = OutboundTag.Block)),
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setProxyPort(4242)
                    .build(),
                BackupPrivateDataV1(
                    relayProfiles = listOf(RelayProfileRecord(id = "relay")),
                    relayCredentials = listOf(RelayCredentialRecord(profileId = "relay", vlessUuid = "secret")),
                ),
            )
        }

    private fun useCase(
        fixture: RoomProfileFixture,
        recorder: ResetEventRecorder,
        session: UserSessionResetStore = UserSessionResetStore {},
        diagnostics: DiagnosticsHistoryResetStore = diagnostics {},
        artifacts: UserArtifactResetStore = UserArtifactResetStore {},
        caches: CacheDirectoryCleaner = caches {},
    ) = ResetAllSettingsUseCase(
        recorder,
        fixture.settings,
        fixture.coordinator,
        session,
        diagnostics,
        artifacts,
        caches,
    )

    @Test
    fun `reset clears canonical data before ancillary stores and preserves previous settings for artifact cleanup`() =
        runTest {
            val fixture = seededFixture()
            val recorder = FakeResetEventRecorder()
            val phases = mutableListOf<String>()
            var artifactPort: Int? = null
            useCase(
                fixture,
                recorder,
                session =
                    UserSessionResetStore {
                        assertTrue(fixture.groups.list().isEmpty())
                        assertTrue(fixture.relayProfiles.list().isEmpty())
                        assertTrue(fixture.relayCredentials.load("relay") == null)
                        assertTrue(
                            fixture.database
                                .ruleDao()
                                .allRules()
                                .first()
                                .isEmpty(),
                        )
                        assertEquals(AppSettingsSerializer.defaultValue, fixture.settings.snapshot())
                        phases += "session"
                    },
                diagnostics = diagnostics { phases += "diagnostics" },
                artifacts =
                    UserArtifactResetStore {
                        artifactPort = it.proxyPort
                        phases += "artifacts"
                    },
                caches = caches { phases += "caches" },
            ).reset()

            assertEquals(listOf("session", "diagnostics", "artifacts", "caches"), phases)
            assertEquals(4242, artifactPort)
        }

    @Test
    fun `reset records the user event before the first database write`() =
        runTest {
            val fixture = seededFixture()
            val recorder = FakeResetEventRecorder()
            var eventWasPending = false
            fixture.beforeSeal = { _, _ -> eventWasPending = recorder.hasPendingResetEvent() }

            useCase(fixture, recorder).reset()

            assertTrue(eventWasPending)
            assertEquals(1, recorder.recordCalls)
        }

    @Test
    fun `reset event survives the wipe and can be consumed once`() =
        runTest {
            val recorder = FakeResetEventRecorder()
            useCase(seededFixture(), recorder).reset()

            assertTrue(recorder.hasPendingResetEvent())
            assertTrue(recorder.consumeResetEvent())
            assertFalse(recorder.consumeResetEvent())
            assertFalse(recorder.hasPendingResetEvent())
        }

    @Test
    fun `reset completes ancillary phases after caller cancellation`() =
        runTest {
            val fixture = seededFixture()
            val started = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val phases = mutableListOf<String>()
            val job =
                backgroundScope.launch {
                    useCase(
                        fixture,
                        FakeResetEventRecorder(),
                        session =
                            UserSessionResetStore {
                                started.complete(Unit)
                                proceed.await()
                                phases += "session"
                            },
                        diagnostics = diagnostics { phases += "diagnostics" },
                        artifacts = UserArtifactResetStore { phases += "artifacts" },
                        caches = caches { phases += "caches" },
                    ).reset()
                }
            started.await()
            job.cancel()
            proceed.complete(Unit)
            job.join()

            assertEquals(listOf("session", "diagnostics", "artifacts", "caches"), phases)
            assertTrue(fixture.groups.list().isEmpty())
        }

    private fun diagnostics(action: suspend () -> Unit) =
        object : DiagnosticsHistoryResetStore {
            override suspend fun clearRuntimeHistory() = action()
        }

    private fun caches(action: () -> Unit) =
        object : CacheDirectoryCleaner {
            override fun clearCaches() = action()
        }

    private class FakeResetEventRecorder : ResetEventRecorder {
        var recordCalls = 0
            private set
        private var pending = false

        override fun recordResetInitiated() {
            recordCalls++
            pending = true
        }

        override fun hasPendingResetEvent(): Boolean = pending

        override fun consumeResetEvent(): Boolean = pending.also { pending = false }
    }
}
