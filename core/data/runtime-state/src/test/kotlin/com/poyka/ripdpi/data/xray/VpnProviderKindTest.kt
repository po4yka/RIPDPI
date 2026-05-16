package com.poyka.ripdpi.data.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Exhaustiveness coverage for [VpnProviderKind].
 *
 * The when-expression over a sealed class is exhaustive at compile time, but
 * these tests document the expected variant set and guard against accidental
 * removal of a variant during refactors.
 */
class VpnProviderKindTest {
    @Test
    fun `VpnProviderKind has exactly two variants`() {
        val variants = VpnProviderKind.entries
        assertEquals(2, variants.size)
    }

    @Test
    fun `Native variant exists`() {
        val kind = VpnProviderKind.valueOf("Native")
        assertNotNull(kind)
        assertEquals(VpnProviderKind.Native, kind)
    }

    @Test
    fun `Xray variant exists`() {
        val kind = VpnProviderKind.valueOf("Xray")
        assertNotNull(kind)
        assertEquals(VpnProviderKind.Xray, kind)
    }

    @Test
    fun `when expression over VpnProviderKind is exhaustive`() {
        // Compile-time exhaustiveness; runtime ensures both arms execute.
        val covered = mutableSetOf<VpnProviderKind>()
        for (kind in VpnProviderKind.entries) {
            val label =
                when (kind) {
                    VpnProviderKind.Native -> "native"
                    VpnProviderKind.Xray -> "xray"
                }
            covered.add(kind)
            assertNotNull(label)
        }
        assertEquals(VpnProviderKind.entries.toSet(), covered)
    }
}
