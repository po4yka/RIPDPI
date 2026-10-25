package com.poyka.ripdpi.proxyimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionLinkClassifierTest {
    @Test
    fun `plain https link is a long-lived subscription`() {
        assertEquals(
            PendingSubscriptionImportRequest("https://sub.example.com/s/abc", name = "", bootstrap = false),
            SubscriptionLinkClassifier.classify("  https://sub.example.com/s/abc\n"),
        )
    }

    @Test
    fun `ripdpi one-shot deep link keeps its bootstrap kind`() {
        assertEquals(
            PendingSubscriptionImportRequest("https://sub.example.com/once", name = "", bootstrap = true),
            SubscriptionLinkClassifier.classify("ripdpi://import?url=https%3A%2F%2Fsub.example.com%2Fonce"),
        )
    }

    @Test
    fun `sing-box deep link keeps its name`() {
        assertEquals(
            PendingSubscriptionImportRequest("https://sub.example.com/s", name = "Home", bootstrap = false),
            SubscriptionLinkClassifier.classify(
                "singbox://import-remote-profile?url=https%3A%2F%2Fsub.example.com%2Fs&name=Home",
            ),
        )
    }

    @Test
    fun `non-https and hostless input is rejected`() {
        assertNull(SubscriptionLinkClassifier.classify("http://sub.example.com/s"))
        assertNull(SubscriptionLinkClassifier.classify("https://"))
        assertNull(SubscriptionLinkClassifier.classify("vless://id@example.com:443"))
        assertNull(SubscriptionLinkClassifier.classify(""))
    }

    @Test
    fun `oversized link is rejected`() {
        assertNull(SubscriptionLinkClassifier.classify("https://sub.example.com/" + "a".repeat(9 * 1024)))
    }
}
