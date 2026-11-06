package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyProfileImportTransactionTest : RoomProfileTest() {
    @Test
    fun `legacy v1 intent commits before authority initialization`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                val fixture = legacy.fixture
                withContext(
                    Dispatchers.IO,
                ) { fixture.awgProfiles.upsertProfile(AwgProfileEntity("legacy", "Legacy", "{}", 1)) }
                legacy.seed(AwgDeleteIntent("legacy"), schemaVersion = 1, origin = null, expected = null)
                fixture.room.beforeSeal = { namespace, _ ->
                    if (namespace == "system") assertFalse(fixture.pauseAuthority.isInitialized())
                }
                fixture.initialize()
                assertNull(withContext(Dispatchers.IO) { fixture.awgProfiles.getProfile("legacy") })
                assertTrue(fixture.pauseAuthority.isInitialized())
                assertNull(legacy.journal.pending())
            }
        }

    @Test
    fun `legacy v1 after initialized authority is preserved and rejected until explicit reset`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                legacy.fixture.pauseAuthority.initializeAfterMigration()
                legacy.seed(AwgDeleteIntent("legacy"), schemaVersion = 1, origin = null, expected = null)
                val before = legacy.legacyBytes()
                val database = legacy.fixture.snapshot()
                assertTrue(runCatching { legacy.fixture.initialize() }.isFailure)
                assertEquals(before, legacy.legacyBytes())
                assertEquals(database, legacy.fixture.snapshot())
                legacy.fixture.coordinator().runReset { }
                assertNull(legacy.journal.pending())
                assertEquals(
                    DesiredRuntimeState.Stopped,
                    legacy.fixture.pauseAuthority
                        .snapshotAuthority()
                        .desired,
                )
            }
        }

    @Test
    fun `v2 import repeats its original fence and preserves newer pause`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                val authority = legacy.fixture.pauseAuthority
                authority.initializeAfterMigration()
                val expected = authority.reference()
                legacy.seed(
                    AwgDeleteIntent("absent"),
                    origin = ProfileMutationOrigin.ExplicitDeletion,
                    expected = expected,
                )
                val newer = authority.begin(Mode.VPN, 300_000, authority.snapshotAuthority())
                legacy.fixture.initialize()
                assertEquals(newer, authority.snapshot())
                assertNull(legacy.journal.pending())
            }
        }

    @Test
    fun `missing provenance and corrupt journal preserve bytes and block all ordinary reads`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                legacy.seed(AwgDeleteIntent("absent"), origin = null)
                val before = legacy.legacyBytes()
                val database = legacy.fixture.snapshot()
                repeat(2) { assertTrue(runCatching { legacy.fixture.settings.snapshot() }.isFailure) }
                assertEquals(before, legacy.legacyBytes())
                assertEquals(database, legacy.fixture.snapshot())
                legacy.fixture.coordinator().runReset { }
                assertNull(legacy.journal.pending())
            }
        }

    @Test
    fun `unreadable encrypted journal never becomes absent or mutates sources`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                check(
                    legacy.context
                        .getSharedPreferences("profile_mutation_journal", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString("pending", "AAAAAA==")
                        .commit(),
                )
                val source = legacy.legacyBytes()
                val before = legacy.fixture.snapshot()
                repeat(2) {
                    assertTrue(
                        runCatching {
                            legacy.fixture.initialize()
                        }.exceptionOrNull() is ProfileMutationJournalCorruptionException,
                    )
                }
                assertEquals(source, legacy.legacyBytes())
                assertEquals(before, legacy.fixture.snapshot())
                legacy.fixture.coordinator().runReset { }
                assertEquals(AppSettingsSerializer.defaultValue, legacy.fixture.settings.snapshot())
                assertNull(legacy.journal.pending())
            }
        }

    @Test
    fun `newer Stop prevents old owned selector intent from replacing providers`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                val authority = legacy.fixture.pauseAuthority
                authority.initializeAfterMigration()
                val expected = authority.reference()
                legacy.seed(
                    SelectorSelectIntent("group", "member", Mode.VPN.preferenceValue),
                    origin = ProfileMutationOrigin.ExplicitActivation,
                    expected = expected,
                )
                val stop = authority.reserveStop()
                val newer = XrayProviderSelectionRecord(XrayProviderSelectionRecord.ProviderKindXray, "newer")
                com.poyka.ripdpi.data.xray
                    .SharedPreferencesXrayProviderSelectionStore(legacy.context)
                    .update(newer)
                legacy.fixture.initialize()
                assertEquals(stop.authority, authority.reference())
                assertEquals(newer, legacy.fixture.xraySelection.current())
                assertNull(
                    legacy.fixture.profileSelection
                        .snapshot()
                        .selectorGroupId,
                )
                assertNull(legacy.fixture.selectorSelections.manualReceipt("group"))
            }
        }

    @Test
    fun `owned selector import retires the original command and does not mint manual capability`() =
        runTest {
            LegacyProfileImportFixture().use { legacy ->
                legacy.seed(
                    SelectorSelectIntent("group", "member", Mode.Proxy.preferenceValue),
                    origin = ProfileMutationOrigin.ExplicitActivation,
                )
                legacy.fixture.initialize()
                val command =
                    checkNotNull(
                        legacy.fixture.pauseAuthority
                            .snapshotAuthority()
                            .command,
                    )
                assertEquals("legacy-import", command.commandId)
                assertEquals(RuntimeActivationPhase.Terminated, command.phase)
                assertEquals(
                    "group",
                    legacy.fixture.profileSelection
                        .snapshot()
                        .selectorGroupId,
                )
                assertEquals(
                    "member",
                    legacy.fixture.selectorSelections
                        .snapshot("group")
                        .profileId,
                )
                assertNull(legacy.fixture.selectorSelections.manualReceipt("group"))
                assertEquals(
                    DesiredRuntimeState.Stopped,
                    legacy.fixture.pauseAuthority
                        .snapshotAuthority()
                        .desired,
                )
                val committed = legacy.fixture.snapshot()
                legacy.fixture.reopen()
                assertEquals(committed, legacy.fixture.snapshot())
                assertTrue(
                    legacy.fixture.pauseAuthority.states.value!!
                        .profileUtility.recents
                        .isEmpty(),
                )
            }
        }
}
