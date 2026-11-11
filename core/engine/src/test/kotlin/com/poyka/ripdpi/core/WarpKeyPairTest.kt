package com.poyka.ripdpi.core

import org.junit.Assert.assertFalse
import org.junit.Test

class WarpKeyPairTest {
    @Test
    fun `keypair debug output excludes key material`() {
        val pair = WarpKeyPair(privateKey = "synthetic-private-key", publicKey = "synthetic-public-key")

        assertFalse(pair.toString().contains(pair.privateKey))
        assertFalse(pair.toString().contains(pair.publicKey))
    }
}
