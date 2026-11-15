package com.poyka.ripdpi.data

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SelectorInitialPreferenceTest : RoomProfileTest() {
    private fun group(initial: String) =
        ProxyGroup(
            id = "g",
            name = "Imported",
            type = ProxyGroupType.SUBSCRIPTION,
            order = 0,
            isSelector = true,
            members = listOf(member("a"), member("b")),
            initialMemberId = initial,
        )

    private fun member(id: String) = ProxyProfile.Trojan(id, id, "g", "$id.example", 443, "fixture")

    @Test
    fun `initial preference survives reopen without publishing active group or manual authority`() =
        runTest {
            val fixture = fixture().also { it.initialize() }
            fixture.groups.add(group("b"))
            assertEquals("b", fixture.selectorSelections.snapshot("g").profileId)
            assertFalse(fixture.selectorSelections.snapshot("g").isManual)
            assertNull(fixture.selectorChoice.activeGroupId.first())
            assertNull(fixture.selectorSelections.manualReceipt("g"))
            fixture.reopen()
            assertEquals("b", fixture.selectorSelections.snapshot("g").profileId)
            assertNull(fixture.selectorChoice.activeGroupId.first())
        }

    @Test
    fun `explicit choices and explicit clear win over later imported defaults`() =
        runTest {
            val fixture = fixture().also { it.initialize() }
            fixture.groups.add(group("b"))
            fixture.selectorSelections.select("g", "a")
            fixture.groups.update(group("b"))
            fixture.reopen()
            assertEquals("a", fixture.selectorSelections.snapshot("g").profileId)
            fixture.selectorSelections.clearSelection("g")
            fixture.groups.update(group("b"))
            fixture.reopen()
            assertNull(fixture.selectorSelections.snapshot("g").profileId)
        }

    @Test
    fun `choice observers follow imported defaults until a local choice exists`() =
        runTest {
            val fixture = fixture().also { it.initialize() }
            fixture.groups.add(group("b"))
            fixture.selectorSelections.selectedProfileId("g").test {
                assertEquals("b", awaitItem())
                fixture.groups.update(group("a"))
                assertEquals("a", awaitItem())
                fixture.selectorSelections.clearSelection("g")
                assertNull(awaitItem())
                fixture.groups.update(group("b"))
                expectNoEvents()
            }
        }

    @Test
    fun `clear all also clears preferences with no stored choice record`() =
        runTest {
            val fixture = fixture().also { it.initialize() }
            fixture.groups.add(group("b"))
            fixture.selectorSelections.clearAll()
            fixture.reopen()
            assertNull(fixture.selectorSelections.snapshot("g").profileId)
        }
}
