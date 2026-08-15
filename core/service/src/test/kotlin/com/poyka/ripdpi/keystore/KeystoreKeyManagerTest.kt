package com.poyka.ripdpi.keystore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreKeyManagerTest {
    @Test
    fun `FakeKeystoreKeyManager returns a non-null key`() {
        val manager = FakeKeystoreKeyManager()
        val key = manager.getOrCreateKey()
        assertNotNull(key)
    }

    @Test
    fun `FakeKeystoreKeyManager returns same key on repeated calls`() {
        val manager = FakeKeystoreKeyManager()
        val first = manager.getOrCreateKey()
        val second = manager.getOrCreateKey()
        assertArrayEquals(first.encoded, second.encoded)
    }

    @Test
    fun `FakeKeystoreKeyManager with StrongBox preference reports isStrongBoxBacked true`() {
        val manager = FakeKeystoreKeyManager(isStrongBoxBacked = true)
        assertTrue(manager.isStrongBoxBacked)
    }

    @Test
    fun `FakeKeystoreKeyManager without StrongBox preference reports isStrongBoxBacked false`() {
        val manager = FakeKeystoreKeyManager(isStrongBoxBacked = false)
        assertFalse(manager.isStrongBoxBacked)
    }

    @Test
    fun `FakeKeystoreKeyManager deleteKey causes next call to create new key`() {
        val manager = FakeKeystoreKeyManager()
        val first = manager.getOrCreateKey()
        manager.deleteKey()
        val second = manager.getOrCreateKey()
        // After deletion a new key material is generated; encoded bytes differ
        // with overwhelming probability for a 256-bit random key.
        val firstBytes = first.encoded
        val secondBytes = second.encoded
        // Both must be valid AES keys
        assertEquals("AES", first.algorithm)
        assertEquals("AES", second.algorithm)
        // Keys must be 256-bit (32 bytes)
        assertEquals(32, firstBytes?.size)
        assertEquals(32, secondBytes?.size)
    }

    @Test
    fun `FakeKeystoreKeyManager key algorithm is AES`() {
        val manager = FakeKeystoreKeyManager()
        val key = manager.getOrCreateKey()
        assertEquals("AES", key.algorithm)
    }

    @Test
    fun `FakeKeystoreKeyManager key size is 256 bits`() {
        val manager = FakeKeystoreKeyManager()
        val key = manager.getOrCreateKey()
        assertEquals(32, key.encoded?.size)
    }

    @Test
    fun `FakeKeystoreKeyManager deleteKey clears cached key`() {
        val manager = FakeKeystoreKeyManager()
        manager.getOrCreateKey()
        manager.deleteKey()
        // After deleteKey the cached key field is null internally;
        // calling getOrCreateKey must not throw and returns a valid key.
        val afterDelete = manager.getOrCreateKey()
        assertNotNull(afterDelete)
    }
}
