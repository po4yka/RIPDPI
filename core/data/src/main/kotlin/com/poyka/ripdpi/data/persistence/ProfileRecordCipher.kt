package com.poyka.ripdpi.data.persistence

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

interface ProfileRecordCipher {
    fun seal(
        namespace: String,
        id: String,
        plaintext: ByteArray,
    ): ByteArray

    fun open(
        namespace: String,
        id: String,
        ciphertext: ByteArray,
    ): ByteArray
}

@Singleton
class KeystoreProfileRecordCipher internal constructor(
    private val keyForWrite: () -> SecretKey,
    private val keyForRead: () -> SecretKey,
) : ProfileRecordCipher {
    @Inject
    constructor() : this({ loadKey(create = true) }, { loadKey(create = false) })

    override fun seal(
        namespace: String,
        id: String,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, keyForWrite())
        cipher.updateAAD(associatedData(namespace, id))
        val encrypted = cipher.doFinal(plaintext)
        check(cipher.iv.size == NonceBytes) { "Invalid profile record nonce size" }
        return byteArrayOf(FormatVersion) + cipher.iv + encrypted
    }

    override fun open(
        namespace: String,
        id: String,
        ciphertext: ByteArray,
    ): ByteArray {
        require(ciphertext.size >= HeaderBytes + TagBytes && ciphertext[0] == FormatVersion) {
            "Invalid encrypted profile record framing"
        }
        val nonce = ciphertext.copyOfRange(1, HeaderBytes)
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.DECRYPT_MODE, keyForRead(), GCMParameterSpec(TagBits, nonce))
        cipher.updateAAD(associatedData(namespace, id))
        return cipher.doFinal(ciphertext, HeaderBytes, ciphertext.size - HeaderBytes)
    }

    private fun associatedData(
        namespace: String,
        id: String,
    ): ByteArray {
        val namespaceBytes = namespace.toByteArray(Charsets.UTF_8)
        val idBytes = id.toByteArray(Charsets.UTF_8)
        return ByteBuffer
            .allocate(Int.SIZE_BYTES * 2 + namespaceBytes.size + idBytes.size)
            .putInt(namespaceBytes.size)
            .put(namespaceBytes)
            .putInt(idBytes.size)
            .put(idBytes)
            .array()
    }

    private companion object {
        const val Provider = "AndroidKeyStore"
        const val KeyAlias = "ripdpi_profile_records"
        const val Transformation = "AES/GCM/NoPadding"
        const val FormatVersion: Byte = 1
        const val NonceBytes = 12
        const val TagBits = 128
        const val TagBytes = TagBits / Byte.SIZE_BITS
        const val HeaderBytes = 1 + NonceBytes
        const val KeyBits = 256

        @Synchronized
        fun loadKey(create: Boolean): SecretKey {
            val keystore = KeyStore.getInstance(Provider).apply { load(null) }
            val existing = keystore.getKey(KeyAlias, null) as? SecretKey
            if (existing != null) return existing
            check(create) { "Profile record encryption key is unavailable" }
            return KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, Provider)
                .apply {
                    init(
                        KeyGenParameterSpec
                            .Builder(
                                KeyAlias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .setKeySize(KeyBits)
                            .build(),
                    )
                }.generateKey()
        }
    }
}
