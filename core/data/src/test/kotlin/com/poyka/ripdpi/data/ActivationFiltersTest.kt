package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationFiltersTest {
    // -- NumericRangeModel.isEmpty --

    @Test
    fun `empty range has both null fields`() {
        assertTrue(NumericRangeModel().isEmpty)
        assertTrue(NumericRangeModel(null, null).isEmpty)
    }

    @Test
    fun `range with start only is not empty`() {
        assertFalse(NumericRangeModel(start = 1).isEmpty)
    }

    @Test
    fun `range with end only is not empty`() {
        assertFalse(NumericRangeModel(end = 5).isEmpty)
    }

    // -- ActivationFilterModel.isEmpty --

    @Test
    fun `default activation filter is empty`() {
        assertTrue(ActivationFilterModel().isEmpty)
    }

    @Test
    fun `activation filter with round set is not empty`() {
        assertFalse(ActivationFilterModel(round = NumericRangeModel(1, 2)).isEmpty)
    }

    // -- normalizeRoundRange --

    @Test
    fun `normalizeRoundRange swaps reversed bounds`() {
        val result = normalizeRoundRange(5, 2)
        assertEquals(NumericRangeModel(2, 5), result)
    }

    @Test
    fun `normalizeRoundRange clamps below 1 to empty`() {
        val result = normalizeRoundRange(0, 0)
        assertEquals(NumericRangeModel(), result)
    }

    @Test
    fun `normalizeRoundRange fills missing start from end`() {
        val result = normalizeRoundRange(null, 3)
        assertEquals(NumericRangeModel(3, 3), result)
    }

    @Test
    fun `normalizeRoundRange fills missing end from start`() {
        val result = normalizeRoundRange(2, null)
        assertEquals(NumericRangeModel(2, 2), result)
    }

    @Test
    fun `normalizeRoundRange returns empty for both null`() {
        assertEquals(NumericRangeModel(), normalizeRoundRange(null, null))
    }

    @Test
    fun `normalizeRoundRange overload delegates to start and end`() {
        assertEquals(
            normalizeRoundRange(1, 3),
            normalizeRoundRange(NumericRangeModel(1, 3)),
        )
    }

    // -- normalizePayloadSizeRange --

    @Test
    fun `normalizePayloadSizeRange allows zero start`() {
        val result = normalizePayloadSizeRange(0, 100)
        assertEquals(NumericRangeModel(0, 100), result)
    }

    @Test
    fun `normalizePayloadSizeRange clamps negative to empty`() {
        val result = normalizePayloadSizeRange(-1, -1)
        assertEquals(NumericRangeModel(), result)
    }

    // -- normalizeStreamBytesRange --

    @Test
    fun `normalizeStreamBytesRange allows zero`() {
        val result = normalizeStreamBytesRange(0, 1199)
        assertEquals(NumericRangeModel(0, 1199), result)
    }

    // -- normalizeActivationFilter --

    @Test
    fun `normalizeActivationFilter normalizes all sub-ranges`() {
        val filter = ActivationFilterModel(
            round = NumericRangeModel(3, 1),
            payloadSize = NumericRangeModel(null, 512),
            streamBytes = NumericRangeModel(0, null),
        )
        val normalized = normalizeActivationFilter(filter)

        assertEquals(NumericRangeModel(1, 3), normalized.round)
        assertEquals(NumericRangeModel(512, 512), normalized.payloadSize)
        assertEquals(NumericRangeModel(0, 0), normalized.streamBytes)
    }

    // -- formatNumericRange --

    @Test
    fun `formatNumericRange returns null for empty range`() {
        assertNull(formatNumericRange(NumericRangeModel()))
    }

    @Test
    fun `formatNumericRange returns single value for equal bounds`() {
        assertEquals("5", formatNumericRange(NumericRangeModel(5, 5)))
    }

    @Test
    fun `formatNumericRange returns dash-separated for different bounds`() {
        assertEquals("1-10", formatNumericRange(NumericRangeModel(1, 10)))
    }

    // -- formatActivationFilterSummary --

    @Test
    fun `formatActivationFilterSummary includes all non-empty ranges`() {
        val filter = ActivationFilterModel(
            round = NumericRangeModel(1, 2),
            payloadSize = NumericRangeModel(64, 512),
            streamBytes = NumericRangeModel(0, 1199),
        )
        assertEquals("round=1-2 size=64-512 stream=0-1199", formatActivationFilterSummary(filter))
    }

    @Test
    fun `formatActivationFilterSummary returns empty string for empty filter`() {
        assertEquals("", formatActivationFilterSummary(ActivationFilterModel()))
    }

    @Test
    fun `formatActivationFilterSummary omits empty sub-ranges`() {
        val filter = ActivationFilterModel(
            round = NumericRangeModel(1, 1),
        )
        assertEquals("round=1", formatActivationFilterSummary(filter))
    }

    // -- parseRoundRange --

    @Test
    fun `parseRoundRange parses single value`() {
        assertEquals(NumericRangeModel(3, 3), parseRoundRange("3"))
    }

    @Test
    fun `parseRoundRange parses range`() {
        assertEquals(NumericRangeModel(1, 5), parseRoundRange("1-5"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseRoundRange rejects zero start`() {
        parseRoundRange("0-5")
    }

    @Test(expected = IllegalStateException::class)
    fun `parseRoundRange rejects non-numeric`() {
        parseRoundRange("abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseRoundRange rejects empty string`() {
        parseRoundRange("")
    }

    // -- parsePayloadSizeRange --

    @Test
    fun `parsePayloadSizeRange allows zero start`() {
        assertEquals(NumericRangeModel(0, 256), parsePayloadSizeRange("0-256"))
    }

    // -- parseStreamBytesRange --

    @Test
    fun `parseStreamBytesRange allows zero`() {
        assertEquals(NumericRangeModel(0, 1199), parseStreamBytesRange("0-1199"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseStreamBytesRange rejects reversed range`() {
        parseStreamBytesRange("100-50")
    }

    // -- Proto round-trip --

    @Test
    fun `NumericRangeModel round trips through proto`() {
        val model = NumericRangeModel(start = 10, end = 20)
        val proto = model.toProto()
        assertEquals(10L, proto.start)
        assertEquals(20L, proto.end)
    }

    @Test
    fun `null values in NumericRangeModel map to sentinel in proto`() {
        val model = NumericRangeModel()
        val proto = model.toProto()
        assertEquals(-1L, proto.start)
        assertEquals(-1L, proto.end)
    }

    @Test
    fun `ActivationFilterModel round trips through proto`() {
        val model = ActivationFilterModel(
            round = NumericRangeModel(1, 3),
            payloadSize = NumericRangeModel(64, 512),
        )
        val proto = model.toProto()
        assertTrue(proto.hasRound())
        assertTrue(proto.hasPayloadSize())
        assertFalse(proto.hasStreamBytes())
    }
}
