package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerExpressionsTest {
    // -- isValidOffsetExpression --

    @Test
    fun `plain numeric offsets are valid`() {
        assertTrue(isValidOffsetExpression("0"))
        assertTrue(isValidOffsetExpression("1"))
        assertTrue(isValidOffsetExpression("-5"))
        assertTrue(isValidOffsetExpression("+10"))
    }

    @Test
    fun `named marker offsets are valid`() {
        assertTrue(isValidOffsetExpression("host"))
        assertTrue(isValidOffsetExpression("host+1"))
        assertTrue(isValidOffsetExpression("endhost"))
        assertTrue(isValidOffsetExpression("endhost-2"))
        assertTrue(isValidOffsetExpression("sld"))
        assertTrue(isValidOffsetExpression("midsld"))
        assertTrue(isValidOffsetExpression("endsld"))
        assertTrue(isValidOffsetExpression("method"))
        assertTrue(isValidOffsetExpression("extlen"))
        assertTrue(isValidOffsetExpression("echext"))
        assertTrue(isValidOffsetExpression("echext+4"))
        assertTrue(isValidOffsetExpression("sniext"))
        assertTrue(isValidOffsetExpression("abs"))
    }

    @Test
    fun `adaptive offsets are valid`() {
        assertTrue(isValidOffsetExpression("auto(balanced)"))
        assertTrue(isValidOffsetExpression("auto(host)"))
        assertTrue(isValidOffsetExpression("auto(midsld)"))
        assertTrue(isValidOffsetExpression("auto(endhost)"))
        assertTrue(isValidOffsetExpression("auto(method)"))
        assertTrue(isValidOffsetExpression("auto(sniext)"))
        assertTrue(isValidOffsetExpression("auto(extlen)"))
    }

    @Test
    fun `adaptive offsets are case insensitive`() {
        assertTrue(isValidOffsetExpression("AUTO(BALANCED)"))
        assertTrue(isValidOffsetExpression("Auto(Host)"))
    }

    @Test
    fun `blank string is not valid`() {
        assertFalse(isValidOffsetExpression(""))
        assertFalse(isValidOffsetExpression("   "))
    }

    @Test
    fun `invalid marker names are rejected`() {
        assertFalse(isValidOffsetExpression("bogus"))
        assertFalse(isValidOffsetExpression("host++1"))
        assertFalse(isValidOffsetExpression("auto(echext)"))
    }

    @Test
    fun `repeat expressions are valid`() {
        assertTrue(isValidOffsetExpression("host+1:3"))
        assertTrue(isValidOffsetExpression("host+1:3:2"))
    }

    @Test
    fun `repeat with zero or negative count is rejected`() {
        assertFalse(isValidOffsetExpression("host+1:0"))
        assertFalse(isValidOffsetExpression("host+1:-1"))
    }

    @Test
    fun `too many colon-separated parts is rejected`() {
        assertFalse(isValidOffsetExpression("host+1:3:2:1"))
    }

    @Test
    fun `empty part between colons is rejected`() {
        assertFalse(isValidOffsetExpression("host+1::3"))
    }

    // -- isAdaptiveOffsetExpression --

    @Test
    fun `isAdaptiveOffsetExpression detects adaptive presets`() {
        assertTrue(isAdaptiveOffsetExpression("auto(balanced)"))
        assertFalse(isAdaptiveOffsetExpression("host+1"))
        assertFalse(isAdaptiveOffsetExpression("auto(unknown)"))
    }

    // -- adaptiveOffsetPreset --

    @Test
    fun `adaptiveOffsetPreset extracts preset name`() {
        assertEquals("balanced", adaptiveOffsetPreset("auto(balanced)"))
        assertEquals("host", adaptiveOffsetPreset("auto(host)"))
        assertEquals("sniext", adaptiveOffsetPreset("auto(sniext)"))
    }

    @Test
    fun `adaptiveOffsetPreset returns null for non-adaptive`() {
        assertNull(adaptiveOffsetPreset("host+1"))
        assertNull(adaptiveOffsetPreset(""))
    }

    // -- formatOffsetExpressionLabel --

    @Test
    fun `formatOffsetExpressionLabel maps adaptive presets`() {
        assertEquals("adaptive balanced", formatOffsetExpressionLabel("auto(balanced)"))
        assertEquals("adaptive host/SNI start", formatOffsetExpressionLabel("auto(host)"))
        assertEquals("adaptive host/SNI middle", formatOffsetExpressionLabel("auto(midsld)"))
        assertEquals("adaptive host/SNI end", formatOffsetExpressionLabel("auto(endhost)"))
        assertEquals("adaptive HTTP method", formatOffsetExpressionLabel("auto(method)"))
        assertEquals("adaptive TLS SNI extension", formatOffsetExpressionLabel("auto(sniext)"))
        assertEquals("adaptive TLS extensions length", formatOffsetExpressionLabel("auto(extlen)"))
    }

    @Test
    fun `formatOffsetExpressionLabel returns raw value for non-adaptive`() {
        assertEquals("host+1", formatOffsetExpressionLabel("host+1"))
        assertEquals("42", formatOffsetExpressionLabel("42"))
    }

    // -- normalizeOffsetExpression --

    @Test
    fun `normalizeOffsetExpression trims whitespace`() {
        assertEquals("host+1", normalizeOffsetExpression("  host+1  ", "0"))
    }

    @Test
    fun `normalizeOffsetExpression uses default for empty`() {
        assertEquals("0", normalizeOffsetExpression("", "0"))
        assertEquals("host+1", normalizeOffsetExpression("  ", "host+1"))
    }
}
