package com.poyka.ripdpi.ui.screens.profiles

import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileUtilityCatalog
import com.poyka.ripdpi.data.ProfileUtilityCatalogEntry
import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.data.ProfileUtilityState
import com.poyka.ripdpi.data.RecentProfileUse
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.RuntimeAppliedUseIdentity
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.RuntimeConfigurationApplyFailure
import com.poyka.ripdpi.data.RuntimeConfigurationApplyReason
import com.poyka.ripdpi.data.RuntimeConfigurationAttempt
import com.poyka.ripdpi.data.RuntimeConfigurationDns
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.RuntimeConfigurationStrategy
import com.poyka.ripdpi.data.testPauseAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUtilityItemProjectionTest {
    private val native = ProfileUtilityReference.NativeRelay("shared")
    private val xray = ProfileUtilityReference.Xray("shared")
    private val firstMember = ProfileUtilityReference.SelectorMember("first", "member")
    private val secondMember = ProfileUtilityReference.SelectorMember("second", "member")
    private val references = listOf(native, xray, firstMember, secondMember)

    @Test
    fun `unready and mismatched generations expose no stale items`() {
        val utility = state(favorites = setOf(native), recents = listOf(recent(native, 1, 100)))
        val applications = mapOf(Mode.VPN to applied(nativeSelection()))
        val measurements: Map<ProfileUtilityReference, ProfileMeasurementUiState> =
            mapOf(native to ProfileMeasurementUiState.Checking)
        for (unavailable in listOf(utility.copy(catalogReady = false), utility.copy(catalogGeneration = 6))) {
            assertTrue(profileUtilityItems(catalog(), unavailable, applications, measurements).isEmpty())
        }
        assertTrue(profileUtilityItems(catalog().copy(generation = 8), utility, applications, measurements).isEmpty())
        assertEquals(
            references,
            profileUtilityItems(catalog(), utility, applications, measurements).map { it.reference },
        )
    }

    @Test
    fun `pruned references and references without metadata do not create rows`() {
        val missing = ProfileUtilityReference.NativeRelay("without-metadata")
        val utility = state(catalogReferences = setOf(firstMember, native, missing), favorites = setOf(missing))
        val metadata =
            catalog().copy(
                entries = listOf(entry(secondMember), entry(firstMember, "Member", "Group", "selector"), entry(native)),
            )
        val items = profileUtilityItems(metadata, utility, emptyMap(), emptyMap())
        assertEquals(listOf(firstMember, native), items.map { it.reference })
        assertEquals("Member", items.first().label)
        assertEquals("Group", items.first().groupLabel)
        assertEquals("selector", items.first().kind)
        assertTrue(items.none { it.favorite || it.applied })
        assertTrue(items.all { it.measurement == ProfileMeasurementUiState.NotChecked })
        assertNull(items.last().groupLabel)
        assertNull(items.last().recentSequence)
        assertNull(items.last().lastUsedAtMillis)
    }

    @Test
    fun `native and xray applied status stay distinct for identical raw ids`() {
        val selections =
            listOf(
                nativeSelection() to native,
                RuntimeConfigurationSelection("Xray", "tcp", "vless", "shared") to xray,
            )
        for ((selection, expected) in selections) {
            val items = profileUtilityItems(catalog(), state(), mapOf(Mode.VPN to applied(selection)), emptyMap())
            assertEquals(listOf(expected), items.filter { it.applied }.map { it.reference })
        }
    }

    @Test
    fun `selector applied status requires exact group and member and takes precedence over profile id`() {
        val otherMember = ProfileUtilityReference.SelectorMember("second", "other-member")
        val metadata = catalog().copy(entries = catalog().entries + entry(otherMember))
        val utility = state(catalogReferences = (references + otherMember).toSet())
        val selection = nativeSelection().copy(selectorGroupId = "second", selectorMemberId = "member")
        val items =
            profileUtilityItems(metadata, utility, mapOf(Mode.Proxy to applied(selection, Mode.Proxy)), emptyMap())
        assertEquals(listOf(secondMember), items.filter { it.applied }.map { it.reference })
        for (incomplete in listOf(selection.copy(selectorGroupId = null), selection.copy(selectorMemberId = " "))) {
            val incompleteItems =
                profileUtilityItems(catalog(), state(), mapOf(Mode.VPN to applied(incomplete)), emptyMap())
            assertTrue(
                incompleteItems.filter { it.reference is ProfileUtilityReference.SelectorMember }.none { it.applied },
            )
        }
    }

    @Test
    fun `applying failed and unknown do not expose previous or requested profiles as applied`() {
        val previous = applied(nativeSelection()).configuration
        val attempt = attempt(RuntimeConfigurationSelection("xray", profileId = "shared"))
        val pending =
            listOf(
                RuntimeConfigurationApplication.Unknown,
                RuntimeConfigurationApplication.Applying(attempt, previous),
                RuntimeConfigurationApplication.Failed(
                    attempt,
                    previous,
                    RuntimeConfigurationApplyFailure.RuntimeRejected,
                ),
            )
        for (application in pending) {
            val items = profileUtilityItems(catalog(), state(), mapOf(Mode.VPN to application), emptyMap())
            assertTrue(items.none { it.applied })
        }
        val mixed =
            mapOf(
                Mode.VPN to pending.last(),
                Mode.Proxy to applied(RuntimeConfigurationSelection("xray", profileId = "shared"), Mode.Proxy),
            )
        assertEquals(
            listOf(xray),
            profileUtilityItems(catalog(), state(), mixed, emptyMap())
                .filter {
                    it.applied
                }.map { it.reference },
        )
    }

    @Test
    fun `only effective selection marks applied and never invents accepted recent use`() {
        val effective = RuntimeConfigurationSelection("xray", profileId = "shared")
        val application =
            applied(effective).let {
                it.copy(configuration = it.configuration.copy(requestedSelection = nativeSelection()))
            }
        val items = profileUtilityItems(catalog(), state(), mapOf(Mode.VPN to application), emptyMap())
        assertEquals(listOf(xray), items.filter { it.applied }.map { it.reference })
        assertTrue(items.all { it.recentSequence == null && it.lastUsedAtMillis == null })
        val other = ProfileUtilityReference.NativeRelay("effective")
        val metadata = catalog().copy(entries = catalog().entries + entry(other))
        val changed =
            applied(nativeSelection().copy(profileId = other.profileId)).let {
                it.copy(configuration = it.configuration.copy(requestedSelection = nativeSelection()))
            }
        assertEquals(
            listOf(other),
            profileUtilityItems(
                metadata,
                state(catalogReferences = (references + other).toSet()),
                mapOf(Mode.VPN to changed),
                emptyMap(),
            ).filter { it.applied }
                .map { it.reference },
        )
    }

    @Test
    fun `missing or unrecognized effective identity does not guess applied status`() {
        val selections =
            listOf(
                RuntimeConfigurationSelection("native", profileId = "shared"),
                nativeSelection().copy(profileId = " "),
                nativeSelection().copy(provider = "unknown"),
                RuntimeConfigurationSelection("xray"),
                nativeSelection().copy(profileId = "deleted"),
            )
        for (selection in selections) {
            assertTrue(
                profileUtilityItems(catalog(), state(), mapOf(Mode.VPN to applied(selection)), emptyMap()).none {
                    it.applied
                },
            )
        }
    }

    @Test
    fun `favorites use the complete typed reference`() {
        for (favorite in references) {
            val items = profileUtilityItems(catalog(), state(favorites = setOf(favorite)), emptyMap(), emptyMap())
            assertEquals(listOf(favorite), items.filter { it.favorite }.map { it.reference })
        }
    }

    @Test
    fun `accepted recents retain exact sequence and timestamp without changing catalog order`() {
        val recents = listOf(recent(secondMember, 12, 900), recent(xray, 5, 300), recent(native, 9, 100))
        val items = profileUtilityItems(catalog(), state(recents = recents), emptyMap(), emptyMap())
        assertEquals(references, items.map { it.reference })
        assertEquals(listOf(9L, 5L, null, 12L), items.map { it.recentSequence })
        assertEquals(listOf(100L, 300L, null, 900L), items.map { it.lastUsedAtMillis })
        assertFalse(items.any { it.applied })
    }

    @Test
    fun `measurement states map by exact typed reference with not checked default`() {
        val measured = ProfileMeasurementUiState.Measured(0, 200)
        val failed = ProfileMeasurementUiState.Failed(ProfileUtilityFailure.TimedOut)
        val measurements =
            mapOf(
                native to ProfileMeasurementUiState.Checking,
                xray to measured,
                firstMember to failed,
                ProfileUtilityReference.SelectorMember("absent", "member") to ProfileMeasurementUiState.Checking,
            )
        val items = profileUtilityItems(catalog(), state(), emptyMap(), measurements)
        assertEquals(
            listOf(
                ProfileMeasurementUiState.Checking,
                measured,
                failed,
                ProfileMeasurementUiState.NotChecked,
            ),
            items.map {
                it.measurement
            },
        )
    }

    @Test
    fun `immutable output captures metadata and measurements independently of mutable input containers`() {
        val entries = catalog().entries.toMutableList()
        val measurements =
            mutableMapOf<ProfileUtilityReference, ProfileMeasurementUiState>(
                native to ProfileMeasurementUiState.Checking,
            )
        val items = profileUtilityItems(ProfileUtilityCatalog(7, entries), state(), emptyMap(), measurements)
        entries.clear()
        measurements.clear()
        assertEquals(references, items.map { it.reference })
        assertEquals(ProfileMeasurementUiState.Checking, items.first().measurement)
    }

    private fun catalog() = ProfileUtilityCatalog(7, references.map { entry(it) })

    private fun entry(
        reference: ProfileUtilityReference,
        label: String = "Profile",
        groupLabel: String? = null,
        kind: String = "vless",
    ) = ProfileUtilityCatalogEntry(reference, label, groupLabel, kind)

    private fun state(
        catalogReferences: Set<ProfileUtilityReference> = references.toSet(),
        favorites: Set<ProfileUtilityReference> = emptySet(),
        recents: List<RecentProfileUse> = emptyList(),
    ) = ProfileUtilityState(
        7,
        true,
        catalogReferences,
        favorites,
        recents,
        recents.maxOfOrNull {
            it.sequence
        } ?: 0,
        emptyList(),
    )

    private fun recent(
        reference: ProfileUtilityReference,
        sequence: Long,
        atMillis: Long,
    ) = RecentProfileUse(
        reference,
        sequence,
        atMillis,
        RuntimeAppliedUseIdentity("runtime", sequence, Mode.VPN.preferenceValue),
    )

    private fun nativeSelection() = RuntimeConfigurationSelection("native", relayKind = "vless", profileId = "shared")

    private fun applied(
        selection: RuntimeConfigurationSelection,
        mode: Mode = Mode.VPN,
    ) = RuntimeConfigurationApplication.Applied(
        AppliedRuntimeConfiguration(
            runtimeId = "runtime",
            revision = 1,
            appliedAt = 800,
            mode = mode,
            requestedSelection = selection,
            effectiveSelection = selection,
            dns = RuntimeConfigurationDns("plain", "system"),
            strategy = RuntimeConfigurationStrategy(false),
            reason = RuntimeConfigurationApplyReason.InitialStart,
        ),
    )

    private fun attempt(selection: RuntimeConfigurationSelection) =
        RuntimeConfigurationAttempt(
            runtimeId = "replacement",
            revision = 2,
            mode = Mode.VPN,
            requestedSelection = selection,
            reason = RuntimeConfigurationApplyReason.UserReconnect,
            originalIntent = RuntimeAppliedIntent.Activation(testPauseAuthority().reserveStart(Mode.VPN)),
            catalogGeneration = 7,
        )
}
