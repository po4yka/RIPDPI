package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.subscription.SubscriptionUserinfo
import com.poyka.ripdpi.data.subscription.withUserinfoHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SubscriptionUserinfo]: parses the `Subscription-Userinfo`
 * response header (`upload=N; download=N; total=N; expire=UNIX_TS`) into typed
 * fields. Robustness contract: every numeric field is `Long?`; a missing or
 * malformed field becomes `null` rather than throwing, so a provider that emits
 * only `expire=` does not break the refresh.
 */
class SubscriptionUserinfoTest {
    @Test
    fun `parses a fully populated header`() {
        val info =
            SubscriptionUserinfo.parse(
                "upload=1024; download=2048; total=10737418240; expire=1893456000",
            )

        assertEquals(1024L, info.upload)
        assertEquals(2048L, info.download)
        assertEquals(10737418240L, info.total)
        assertEquals(1893456000L, info.expire)
    }

    @Test
    fun `tolerates arbitrary whitespace and missing spaces around separators`() {
        val info = SubscriptionUserinfo.parse("upload=1;download=2;  total=3 ;expire=4")

        assertEquals(1L, info.upload)
        assertEquals(2L, info.download)
        assertEquals(3L, info.total)
        assertEquals(4L, info.expire)
    }

    @Test
    fun `a provider emitting only expire does not break the parse`() {
        val info = SubscriptionUserinfo.parse("expire=1893456000")

        assertNull(info.upload)
        assertNull(info.download)
        assertNull(info.total)
        assertEquals(1893456000L, info.expire)
    }

    @Test
    fun `malformed numeric values become null rather than throwing`() {
        val info = SubscriptionUserinfo.parse("upload=NaN; download=; total=12abc; expire=1893456000")

        assertNull(info.upload)
        assertNull(info.download)
        assertNull(info.total)
        assertEquals(1893456000L, info.expire)
    }

    @Test
    fun `an empty or blank header yields an all-null userinfo`() {
        val empty = SubscriptionUserinfo.parse("")
        val blank = SubscriptionUserinfo.parse("   ")

        assertEquals(SubscriptionUserinfo(), empty)
        assertEquals(SubscriptionUserinfo(), blank)
        assertNull(empty.upload)
        assertNull(empty.expire)
    }

    @Test
    fun `unknown keys are ignored`() {
        val info = SubscriptionUserinfo.parse("upload=1; reset_day=15; expire=1893456000")

        assertEquals(1L, info.upload)
        assertEquals(1893456000L, info.expire)
    }

    @Test
    fun `bytes used and remaining are derived from the typed fields`() {
        val info =
            SubscriptionUserinfo.parse("upload=100; download=200; total=1000; expire=1893456000")

        // bytesUsed = upload + download; bytesRemaining = total - used.
        assertEquals(300L, info.bytesUsed)
        assertEquals(700L, info.bytesRemaining)
    }

    @Test
    fun `bytes used and remaining are null when their inputs are missing`() {
        val onlyExpire = SubscriptionUserinfo.parse("expire=1893456000")

        assertNull(onlyExpire.bytesUsed)
        assertNull(onlyExpire.bytesRemaining)
    }

    @Test
    fun `expiry detection flags a past expire timestamp`() {
        val pastExpiry = SubscriptionUserinfo.parse("expire=1000000000")
        // A far-future timestamp is not expired.
        val futureExpiry = SubscriptionUserinfo.parse("expire=4102444800")

        assertTrue(pastExpiry.isExpired(nowEpochSeconds = 2000000000L))
        assertFalse(futureExpiry.isExpired(nowEpochSeconds = 2000000000L))
    }

    @Test
    fun `a userinfo with no expire is never reported as expired`() {
        val noExpire = SubscriptionUserinfo.parse("upload=1; download=2")

        assertFalse(noExpire.isExpired(nowEpochSeconds = 4102444800L))
    }

    @Test
    fun `withUserinfoHeader folds the parsed header into the Subscription entity`() {
        val subscription = Subscription(link = "https://sub.example.com/sub/x")

        val updated =
            subscription.withUserinfoHeader(
                "upload=100; download=200; total=1000; expire=1893456000",
            )

        assertEquals(300L, updated.bytesUsed)
        assertEquals(700L, updated.bytesRemaining)
        assertEquals(1893456000L, updated.expiryDate)
        assertEquals("upload=100; download=200; total=1000; expire=1893456000", updated.subscriptionUserinfo)
        // The unrelated fields are preserved.
        assertEquals("https://sub.example.com/sub/x", updated.link)
    }

    @Test
    fun `withUserinfoHeader leaves accounting fields at zero for a header missing those fields`() {
        val updated = Subscription().withUserinfoHeader("expire=1893456000")

        assertEquals(0L, updated.bytesUsed)
        assertEquals(0L, updated.bytesRemaining)
        assertEquals(1893456000L, updated.expiryDate)
    }
}
