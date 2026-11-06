package com.poyka.ripdpi.data.persistence

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class ProfileRecordCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = KeystoreProfileRecordCipher({ key }, { key })

    @Test
    fun `encrypted records round trip with independent nonces`() {
        val plaintext = "private credentials".toByteArray()
        val first = cipher.seal("credentials", "profile", plaintext)
        val second = cipher.seal("credentials", "profile", plaintext)

        assertFalse(plaintext.contentEquals(first))
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plaintext, cipher.open("credentials", "profile", first))
        assertArrayEquals(plaintext, cipher.open("credentials", "profile", second))
        assertArrayEquals(
            byteArrayOf(),
            cipher.open("settings", "empty", cipher.seal("settings", "empty", byteArrayOf())),
        )
    }

    @Test
    fun `record location is authenticated with unambiguous field boundaries`() {
        val encrypted = cipher.seal("ab", "c", byteArrayOf(1))

        assertThrows(AEADBadTagException::class.java) { cipher.open("a", "bc", encrypted) }
        assertThrows(AEADBadTagException::class.java) { cipher.open("ab", "other", encrypted) }
    }

    @Test
    fun `changed nonce ciphertext or tag cannot be decrypted`() {
        val encrypted = cipher.seal("credentials", "profile", "secret".toByteArray())
        listOf(1, 13, encrypted.lastIndex).forEach { index ->
            val changed = encrypted.copyOf()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            assertThrows(AEADBadTagException::class.java) { cipher.open("credentials", "profile", changed) }
        }
    }

    @Test
    fun `invalid framing fails before key access`() {
        val noKey =
            KeystoreProfileRecordCipher({ error("Unexpected key creation") }, { error("Unexpected key access") })
        val malformed = listOf(byteArrayOf(), byteArrayOf(1), ByteArray(28), ByteArray(29) { 2 })
        malformed.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) { noKey.open("credentials", "profile", payload) }
        }
    }

    @Test
    fun `missing read key propagates and does not create a replacement`() {
        val encrypted = cipher.seal("credentials", "profile", byteArrayOf(1))
        val noKey = KeystoreProfileRecordCipher({ error("Unexpected key creation") }, { throw MissingKeyException() })

        assertThrows(MissingKeyException::class.java) { noKey.open("credentials", "profile", encrypted) }
    }

    private class MissingKeyException : IllegalStateException()
}
