package com.poyka.ripdpi.keystore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedProfileStoreTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private fun makeStore(): EncryptedProfileStore =
        EncryptedProfileStore(
            storageDir = tmpDir.newFolder("profiles"),
            keyManager = FakeKeystoreKeyManager(),
        )

    @Test
    fun `write then read returns original plaintext`() {
        val store = makeStore()
        val plaintext = "fixture-profile-content-1".toByteArray(Charsets.UTF_8)
        store.write("fixture-profile-1", plaintext)
        val decrypted = store.read("fixture-profile-1")
        assertNotNull(decrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `ciphertext bytes written to disk differ from plaintext`() {
        val store = makeStore()
        val plaintext = "fixture-profile-content-2".toByteArray(Charsets.UTF_8)
        val ciphertext = store.write("fixture-profile-2", plaintext)
        // Blob must not equal plaintext
        assertFalse(
            "ciphertext must differ from plaintext",
            ciphertext.contentEquals(plaintext),
        )
    }

    @Test
    fun `on-disk file does not contain plaintext bytes`() {
        val dir = tmpDir.newFolder("profiles-check")
        val store = EncryptedProfileStore(storageDir = dir, keyManager = FakeKeystoreKeyManager())
        val plaintext = "fixture-sensitive-password-3".toByteArray(Charsets.UTF_8)
        store.write("fixture-profile-3", plaintext)
        val diskBytes = java.io.File(dir, "fixture-profile-3.enc").readBytes()
        // Verify the plaintext string does not appear verbatim anywhere in the file
        val diskString = diskBytes.toString(Charsets.ISO_8859_1)
        assertFalse(
            "plaintext must not appear on disk",
            diskString.contains("fixture-sensitive-password-3"),
        )
    }

    @Test
    fun `read returns null when no blob exists`() {
        val store = makeStore()
        assertNull(store.read("no-such-profile"))
    }

    @Test
    fun `contains returns false before write`() {
        val store = makeStore()
        assertFalse(store.contains("not-yet-written"))
    }

    @Test
    fun `contains returns true after write`() {
        val store = makeStore()
        store.write("fixture-profile-4", "data".toByteArray())
        assertTrue(store.contains("fixture-profile-4"))
    }

    @Test
    fun `delete removes blob so read returns null`() {
        val store = makeStore()
        store.write("fixture-profile-5", "to-delete".toByteArray())
        store.delete("fixture-profile-5")
        assertNull(store.read("fixture-profile-5"))
    }

    @Test
    fun `two writes of same profile id produce same decrypted content`() {
        val store = makeStore()
        val plaintext = "fixture-profile-content-round-trip".toByteArray(Charsets.UTF_8)
        store.write("fixture-profile-6", plaintext)
        store.write("fixture-profile-6", plaintext)
        val decrypted = store.read("fixture-profile-6")
        assertArrayEquals(plaintext, decrypted)
    }
}
