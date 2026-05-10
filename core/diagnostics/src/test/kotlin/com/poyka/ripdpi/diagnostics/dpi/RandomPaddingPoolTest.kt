package com.poyka.ripdpi.diagnostics.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RandomPaddingPoolTest {
    @Test
    fun poolContainsOneHundredKilobytesOfAsciiPadding() {
        val pool = RandomPaddingPool(random = Random(7))

        assertEquals(100 * 1024, pool.sizeBytes)
        assertTrue(pool.slice(4096).all { char -> char.code in PrintableAsciiRange })
    }

    @Test
    fun sliceReturnsRequestedSizeWithoutMutatingPool() {
        val pool = RandomPaddingPool(random = Random(11))

        val first = pool.slice(4096)
        val second = pool.slice(4096)

        assertEquals(4096, first.length)
        assertEquals(4096, second.length)
        assertEquals(100 * 1024, pool.sizeBytes)
        assertNotEquals(first, second)
    }

    private companion object {
        private val PrintableAsciiRange = 0x20..0x7e
    }
}
