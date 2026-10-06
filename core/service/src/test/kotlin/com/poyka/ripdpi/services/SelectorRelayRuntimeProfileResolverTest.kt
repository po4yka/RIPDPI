package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.DefaultRelayLocalSocksHost
import com.poyka.ripdpi.data.DefaultRelayLocalSocksPort
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.selector.SelectorActiveGroupStore
import com.poyka.ripdpi.data.selector.SelectorSelectionSnapshot
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import com.poyka.ripdpi.data.selector.SharedPreferencesSelectorSelectionStore
import com.poyka.ripdpi.data.testMutationPreparationSource
import com.poyka.ripdpi.data.testPauseAuthority
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectorRelayRuntimeProfileResolverTest {
    private val context = org.robolectric.RuntimeEnvironment.getApplication()

    @Before
    fun clearSelections() {
        context
            .getSharedPreferences("selector_selection_store", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `null active selector does not look up groups or a selected member`() =
        runTest {
            val repository = RecordingGroupRepository(listOf(group("group-a")))
            val selections = selectorStores(null, null)
            var selectionReads = 0
            val selectionReader =
                object : SelectorSelectionStore by selections.second {
                    override fun snapshot(groupId: String): SelectorSelectionSnapshot {
                        selectionReads += 1
                        return selections.second.snapshot(groupId)
                    }
                }
            val resolver = DefaultSelectorRelayRuntimeProfileResolver(selections.first, selectionReader, repository)

            assertNull(resolver.resolve())
            assertEquals(0, repository.listCalls)
            assertEquals(0, selectionReads)
        }

    @Test
    fun `explicit group B wins despite group A order and identical member IDs`() =
        runTest {
            val memberA = member("group-a", "shared")
            val memberB = member("group-b", "shared")
            val repository = RecordingGroupRepository(listOf(group("group-a", memberA), group("group-b", memberB, 9)))

            val selected = requireNotNull(selectorResolver("group-b", "shared", repository).resolve())

            assertEquals("group-b", selected.groupId)
            assertEquals("shared", selected.memberId)
            assertEquals("shared", selected.profile.id)
            assertEquals("group-b.example", selected.profile.server)
            assertEquals(memberB.password, selected.credentials.trojanPassword)
            assertFalse(memberA.password == selected.credentials.trojanPassword)
        }

    @Test
    fun `same member ID in two groups retains each group identity and payload`() =
        runTest {
            val repository = RecordingGroupRepository(listOf(group("group-a"), group("group-b")))
            val selectedA = requireNotNull(selectorResolver("group-a", "shared", repository).resolve())
            val selectedB = requireNotNull(selectorResolver("group-b", "shared", repository).resolve())

            assertEquals(selectedA.memberId, selectedB.memberId)
            assertEquals("group-a", selectedA.groupId)
            assertEquals("group-b", selectedB.groupId)
            assertEquals("group-a.example", selectedA.profile.server)
            assertEquals("group-b.example", selectedB.profile.server)
            assertFalse(selectedA.credentials == selectedB.credentials)
        }

    @Test
    fun `missing explicit group non selector group and missing selection fail closed`() =
        runTest {
            val validGroup = group("group-b")
            val cases =
                listOf(
                    "missing" to listOf(validGroup),
                    "group-b" to listOf(validGroup.copy(isSelector = false)),
                    "group-b" to listOf(validGroup),
                )
            for ((groupId, groups) in cases) {
                assertRejected { selectorResolver(groupId, null, RecordingGroupRepository(groups)).resolve() }
            }
        }

    @Test
    fun `missing ambiguous or foreign selected member fails closed`() =
        runTest {
            val member = member("group-b", "shared")
            val groups =
                listOf(
                    group("group-b", member.copy(id = "other")),
                    group("group-b", member).copy(members = listOf(member, member)),
                    group("group-b", member.copy(groupId = "group-a")),
                )
            for (group in groups) {
                assertRejected {
                    selectorResolver(
                        "group-b",
                        "shared",
                        RecordingGroupRepository(listOf(group)),
                    ).resolve()
                }
            }
        }

    @Test
    fun `ambiguous explicit group fails closed`() =
        runTest {
            val group = group("group-b")
            assertRejected {
                selectorResolver("group-b", "shared", RecordingGroupRepository(listOf(group, group))).resolve()
            }
        }

    @Test
    fun `unsupported selected member fails closed`() =
        runTest {
            val member = ProxyProfile.RawConfig("shared", "opaque", "group-b", "{}")
            assertRejected {
                selectorResolver(
                    "group-b",
                    "shared",
                    RecordingGroupRepository(listOf(group("group-b", member))),
                ).resolve()
            }
        }

    @Test
    fun `invalid mapped member fields produce typed rejection`() =
        runTest {
            val member =
                ProxyProfile.Vless(
                    id = "shared",
                    displayName = "invalid",
                    groupId = "group-b",
                    server = "group-b.example",
                    serverPort = 443,
                    uuid = "invalid",
                    serverName = "group-b.example",
                    xhttpPath = "/xhttp",
                )
            assertRejected {
                selectorResolver(
                    "group-b",
                    "shared",
                    RecordingGroupRepository(listOf(group("group-b", member))),
                ).resolve()
            }
        }

    @Test
    fun `disabled standalone config resolves transient selection without overwriting colliding stores`() =
        runTest {
            val standalone = RelayProfileRecord(id = "shared", kind = RelayKindTrojan, server = "standalone.example")
            val standaloneCredentials =
                RelayCredentialRecord(profileId = "shared", trojanPassword = credential("standalone"))
            val profiles = TestRelayProfileStore().apply { save(standalone) }
            val credentials = TestRelayCredentialStore().apply { save(standaloneCredentials) }
            val initialProfiles = profiles.profiles.toMap()
            val initialCredentials = credentials.credentials.toMap()
            val nativeReads = NativeReads(profiles, credentials)
            val selectedMember = member("group-b", "shared").copy(serverPort = 8443, serverName = "sni.example")
            val selector =
                selectorResolver(
                    "group-b",
                    "shared",
                    RecordingGroupRepository(listOf(group("group-b", selectedMember))),
                )
            val selected = requireNotNull(selector.resolve())
            assertEquals(
                RelayProfileRecord(
                    id = "shared",
                    kind = RelayKindTrojan,
                    server = "group-b.example",
                    serverPort = 8443,
                    serverName = "sni.example",
                    udpEnabled = true,
                ),
                selected.profile,
            )
            assertEquals(
                RelayCredentialRecord("shared", trojanPassword = selectedMember.password, updatedAtEpochMillis = 0L),
                selected.credentials,
            )

            val resolved =
                upstreamResolver(selector, nativeReads).resolve(
                    RipDpiRelayConfig(enabled = false, profileId = "shared"),
                    testRelayResolutionInputs(quic = OwnedRelayQuicMigrationConfig(bindLowPort = true)),
                )

            assertTrue(resolved.enabled)
            assertEquals(RelayKindTrojan, resolved.kind)
            assertEquals("shared", resolved.profileId)
            assertEquals("group-b.example", resolved.server)
            assertEquals(8443, resolved.serverPort)
            assertEquals("sni.example", resolved.serverName)
            assertEquals(selectedMember.password, resolved.trojanPassword)
            assertEquals(DefaultRelayLocalSocksHost, resolved.localSocksHost)
            assertEquals(DefaultRelayLocalSocksPort, resolved.localSocksPort)
            assertTrue(resolved.tcpFallbackEnabled)
            assertTrue(resolved.udpEnabled)
            assertTrue(resolved.quicBindLowPort)
            assertEquals(0, nativeReads.profileLoads)
            assertEquals(0, nativeReads.credentialLoads)
            assertEquals(initialProfiles, profiles.profiles)
            assertEquals(initialCredentials, credentials.credentials)
        }

    @Test
    fun `null active group preserves standalone relay resolution`() =
        runTest {
            val profiles =
                TestRelayProfileStore().apply {
                    save(
                        RelayProfileRecord(
                            id = "native",
                            kind = RelayKindTrojan,
                            server = "standalone.example",
                            serverName = "standalone.example",
                        ),
                    )
                }
            val credentials =
                TestRelayCredentialStore().apply {
                    save(RelayCredentialRecord("native", trojanPassword = credential("standalone")))
                }
            val nativeReads = NativeReads(profiles, credentials)
            val repository = RecordingGroupRepository(listOf(group("group-a")))
            val selector = selectorResolver(null, null, repository)
            val resolved =
                upstreamResolver(selector, nativeReads).resolve(
                    RipDpiRelayConfig(enabled = false, profileId = "native", kind = RelayKindTrojan),
                    testRelayResolutionInputs(quic = OwnedRelayQuicMigrationConfig()),
                )

            assertFalse(resolved.enabled)
            assertEquals("native", resolved.profileId)
            assertEquals("standalone.example", resolved.server)
            assertEquals(credential("standalone"), resolved.trojanPassword)
            assertEquals(0, repository.listCalls)
            assertEquals(1, nativeReads.profileLoads)
            assertEquals(1, nativeReads.credentialLoads)
        }

    @Test
    fun `invalid active group is rejected before reading valid standalone stores`() =
        runTest {
            val profiles =
                TestRelayProfileStore().apply {
                    save(RelayProfileRecord(id = "shared", kind = RelayKindTrojan, server = "standalone.example"))
                }
            val credentials =
                TestRelayCredentialStore().apply {
                    save(RelayCredentialRecord("shared", trojanPassword = credential("standalone")))
                }
            val nativeReads = NativeReads(profiles, credentials)
            val selector = selectorResolver("missing", "shared", RecordingGroupRepository(listOf(group("group-a"))))

            assertRejected {
                upstreamResolver(selector, nativeReads).resolve(
                    RipDpiRelayConfig(enabled = true, profileId = "shared", kind = RelayKindTrojan),
                    testRelayResolutionInputs(quic = OwnedRelayQuicMigrationConfig()),
                )
            }
            assertEquals(0, nativeReads.profileLoads)
            assertEquals(0, nativeReads.credentialLoads)
        }

    private fun selectorStores(
        groupId: String?,
        memberId: String?,
    ): Pair<SelectorActiveGroupStore, SelectorSelectionStore> {
        val preferences = context.getSharedPreferences("selector_selection_store", Context.MODE_PRIVATE)
        preferences
            .edit()
            .clear()
            .putString("active_group_id", groupId.orEmpty())
            .apply {
                if (groupId != null && memberId != null) putString("selected-profile-$groupId", memberId)
            }.commit()
        val authority = testPauseAuthority()
        val activeGroup = SelectorActiveGroupStore(context, authority)
        val selections =
            SharedPreferencesSelectorSelectionStore(
                context,
                testMutationPreparationSource(),
                authority,
                activeGroup,
            )
        return activeGroup to selections
    }

    private fun selectorResolver(
        groupId: String?,
        memberId: String?,
        repository: ProxyGroupRepository,
    ): DefaultSelectorRelayRuntimeProfileResolver {
        val (activeGroup, selections) = selectorStores(groupId, memberId)
        return DefaultSelectorRelayRuntimeProfileResolver(activeGroup, selections, repository)
    }

    private fun group(
        id: String,
        member: ProxyProfile = member(id, "shared"),
        order: Int = 0,
    ): ProxyGroup = ProxyGroup(id, id, ProxyGroupType.BASIC, order, true, members = listOf(member))

    private fun member(
        groupId: String,
        id: String,
    ): ProxyProfile.Trojan =
        ProxyProfile.Trojan(
            id = id,
            displayName = id,
            groupId = groupId,
            server = "$groupId.example",
            serverPort = 443,
            password = credential(groupId),
        )

    private fun credential(label: String): String = "selector-test-credential-$label"

    private suspend fun assertRejected(block: suspend () -> Any?) {
        try {
            block()
            fail("Expected a typed selector resolution rejection")
        } catch (error: ServiceStartupRejectedException) {
            assertTrue(error.reason is FailureReason.RelayConfigRejected)
        }
    }

    private fun upstreamResolver(
        selector: SelectorRelayRuntimeProfileResolver,
        nativeReads: NativeReads,
    ): DefaultUpstreamRelayRuntimeConfigResolver =
        DefaultUpstreamRelayRuntimeConfigResolver(
            relayRuntimeProfileReader = RelayRuntimeProfileReader(nativeReads.profiles, nativeReads.credentials),
            selectorRelayRuntimeProfileResolver = selector,
            relayKindResolverRegistry =
                createDefaultRelayKindResolverRegistry(
                    nativeReads.profiles,
                    nativeReads.credentials,
                    object : CloudflareMasqueGeohashResolver {
                        override suspend fun resolveHeaderValue(): String? = null
                    },
                    StaticMasquePrivacyPassProvider(),
                ),
            torRuntimePathProvider = StaticTorRuntimePathProvider(),
            torPluggableTransportProvider = StaticTorPluggableTransportProvider(),
        )

    private class RecordingGroupRepository(
        initial: List<ProxyGroup>,
    ) : ProxyGroupRepository by TestProxyGroupRepository(initial) {
        private val groups = initial
        var listCalls = 0
            private set

        override suspend fun list(): List<ProxyGroup> {
            listCalls += 1
            return groups
        }
    }

    private class NativeReads(
        profileStore: RelayProfileStore,
        credentialStore: RelayCredentialStore,
    ) {
        var profileLoads = 0
            private set
        var credentialLoads = 0
            private set
        val profiles =
            object : RelayProfileStore by profileStore {
                override suspend fun load(profileId: String): RelayProfileRecord? {
                    profileLoads += 1
                    return profileStore.load(profileId)
                }
            }
        val credentials =
            object : RelayCredentialStore by credentialStore {
                override suspend fun load(profileId: String): RelayCredentialRecord? {
                    credentialLoads += 1
                    return credentialStore.load(profileId)
                }
            }
    }
}
