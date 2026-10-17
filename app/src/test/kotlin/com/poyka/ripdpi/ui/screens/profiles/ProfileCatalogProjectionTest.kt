package com.poyka.ripdpi.ui.screens.profiles

import com.poyka.ripdpi.data.ProfileUtilityReference
import com.poyka.ripdpi.ui.components.profiles.matchesProfileQuery
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCatalogProjectionTest {
    @Test
    fun `sections retain source order and header while promoting applied rows within each section`() {
        val firstNative = profile("first-native")
        val firstMember = profile("first-member", ProfileUtilityReference.SelectorMember("group", "one"))
        val secondNative = profile("second-native").copy(applied = true)
        val secondMember =
            profile("second-member", ProfileUtilityReference.SelectorMember("group", "two")).copy(
                applied = true,
                groupLabel = "Different display label",
            )
        val xray = profile("xray", ProfileUtilityReference.Xray("xray"))
        val secondGroup = profile("another-group", ProfileUtilityReference.SelectorMember("other", "one"))
        val input = persistentListOf(firstNative, firstMember, secondNative, secondMember, xray, secondGroup)

        val result = projectProfileCatalog(input, "", null)

        assertEquals(input, result.visible)
        assertEquals(listOf("native", "selector:group", "xray", "selector:other"), result.sections.map { it.key })
        assertEquals(listOf(secondNative, firstNative), result.sections[0].profiles)
        assertEquals(listOf(secondMember, firstMember), result.sections[1].profiles)
        assertEquals(firstMember, result.sections[1].titleProfile)
        assertEquals(listOf(xray), result.sections[2].profiles)
    }

    @Test
    fun `equal applied ranks keep catalog order and input remains unchanged`() {
        val input =
            persistentListOf(
                profile("one"),
                profile("two").copy(applied = true),
                profile("three"),
                profile("four").copy(applied = true),
            )

        val result = projectProfileCatalog(input, "", null)

        assertEquals(
            listOf("two", "four", "one", "three"),
            result.sections
                .single()
                .profiles
                .map { it.label },
        )
        assertEquals(listOf("one", "two", "three", "four"), input.map { it.label })
    }

    @Test
    fun `recent rows use global descending sequence with stable ties and no applied promotion`() {
        val applied = profile("applied").copy(applied = true, recentSequence = 1)
        val newest = profile("newest", ProfileUtilityReference.Xray("newest")).copy(recentSequence = 9)
        val tied = profile("tied").copy(recentSequence = 9)
        val neverUsed = profile("never-used")

        val result = projectProfileCatalog(persistentListOf(applied, newest, tied, neverUsed), "", "recent")

        assertEquals(listOf(newest, tied, applied), result.visible)
        assertTrue(result.sections.isEmpty())
    }

    @Test
    fun `favorite and query filters cannot reintroduce applied or nonmatching rows`() {
        val applied = profile("applied").copy(applied = true)
        val favorite = profile("Travel").copy(favorite = true)
        val otherFavorite = profile("Home").copy(favorite = true)
        val result = projectProfileCatalog(persistentListOf(applied, favorite, otherFavorite), "travel", "favorites")

        assertEquals(listOf(favorite), result.visible)
        assertEquals(listOf(favorite), result.sections.single().profiles)
    }

    @Test
    fun `query normalization retains existing whitespace unicode case and multi field matching`() {
        val input =
            listOf(
                profile("Travel İstanbul").copy(groupLabel = "City 東京"),
                profile("TRAVEL").copy(kind = "WireGuard"),
                profile("дом").copy(groupLabel = "Москва", kind = "Туннель"),
                profile("plain"),
            ).toImmutableList()
        val queries =
            listOf("", " \t\n ", " travel ", "travel\t東京", "istanbul CITY", "travel wireguard", "ДОМ МОСКВА", "missing")

        queries.forEach { query ->
            val expected =
                input.filter {
                    matchesProfileQuery(query, listOf(it.label, it.groupLabel.orEmpty(), it.kind))
                }
            assertEquals("Query: $query", expected, projectProfileCatalog(input, query, null).visible)
        }
    }

    @Test
    fun `empty and unknown filters preserve empty state and all rows respectively`() {
        val empty = projectProfileCatalog(persistentListOf(), "", null)
        assertTrue(empty.visible.isEmpty())
        assertTrue(empty.sections.isEmpty())
        val input = persistentListOf(profile("one"), profile("two"))
        assertEquals(input, projectProfileCatalog(input, "", "unknown").visible)
        val noMatch = projectProfileCatalog(input, "missing", "favorites")
        assertTrue(noMatch.visible.isEmpty())
        assertTrue(noMatch.sections.isEmpty())
        assertFalse(projectProfileCatalog(input, "", null).sections.isEmpty())
    }

    private fun profile(
        label: String,
        reference: ProfileUtilityReference = ProfileUtilityReference.NativeRelay(label),
    ) = ProfileUtilityItem(
        reference = reference,
        label = label,
        groupLabel = null,
        kind = "Shadowsocks",
        favorite = false,
        applied = false,
        lastUsedAtMillis = null,
        recentSequence = null,
        measurement = ProfileMeasurementUiState.NotChecked,
    )
}
