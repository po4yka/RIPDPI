package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUtilityStateTest {
    @Test
    fun `same raw ID remains distinct across namespace and selector group`() {
        val references =
            setOf(
                ProfileUtilityReference.NativeRelay("same"),
                ProfileUtilityReference.Xray("same"),
                ProfileUtilityReference.SelectorMember("first", "same"),
                ProfileUtilityReference.SelectorMember("second", "same"),
            )
        assertEquals(4, references.size)
    }

    @Test
    fun `positive applied receipt records once and conflicting duplicate changes nothing`() {
        val reference = ProfileUtilityReference.NativeRelay("one")
        val initial = ProfileUtilityState.empty().copy(catalog = setOf(reference))
        val receipt = receipt(reference)
        val first = initial.acceptApplied(receipt)
        assertEquals(1L, first.lastSequence)
        assertEquals(listOf(reference), first.recents.map { it.reference })
        assertEquals(first, first.acceptApplied(receipt))
        assertTrue(runCatching { first.acceptApplied(receipt.copy(appliedAtMillis = 201)) }.isFailure)
        assertEquals(1L, first.lastSequence)
    }

    @Test
    fun `deleted catalog and stale generation cannot introduce a recent`() {
        val reference = ProfileUtilityReference.NativeRelay("one")
        val deleted = ProfileUtilityState.empty().copy(catalogGeneration = 1)
        assertTrue(runCatching { deleted.acceptApplied(receipt(reference)) }.isFailure)
        assertTrue(deleted.recents.isEmpty())
        assertEquals(0L, deleted.lastSequence)
    }

    @Test
    fun `sequence overflow rejects instead of clamping or changing ordering`() {
        val reference = ProfileUtilityReference.NativeRelay("one")
        val full = ProfileUtilityState.empty().copy(catalog = setOf(reference), lastSequence = Long.MAX_VALUE)
        assertTrue(runCatching { full.acceptApplied(receipt(reference)) }.exceptionOrNull() is ArithmeticException)
        assertTrue(full.recents.isEmpty())
    }

    @Test
    fun `same session refresh acknowledges but never invents another successful use`() {
        val reference = ProfileUtilityReference.NativeRelay("one")
        val initial = ProfileUtilityState.empty().copy(catalog = setOf(reference))
        val first = initial.acceptApplied(receipt(reference))
        val refresh =
            receipt(
                reference,
            ).copy(identity = RuntimeAppliedUseIdentity("runtime", 2, Mode.Proxy.preferenceValue), recordsUse = false)
        val next = first.acceptApplied(refresh)
        assertEquals(first.recents, next.recents)
        assertEquals(first.lastSequence, next.lastSequence)
        assertFalse(next.acknowledged.isEmpty())
    }

    private fun receipt(reference: ProfileUtilityReference) =
        RuntimeAppliedUseReceipt(
            RuntimeAppliedUseIdentity("runtime", 1, Mode.Proxy.preferenceValue),
            listOf(reference),
            200,
            0,
            true,
            "0".repeat(64),
        )
}
