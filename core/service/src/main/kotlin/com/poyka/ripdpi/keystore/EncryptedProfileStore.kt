package com.poyka.ripdpi.keystore

import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val AesGcmTransformation = "AES/GCM/NoPadding"
private const val GcmTagLenBits = 128
private const val IvLenHeaderBytes = 4
private const val Byte0Shift = 24
private const val Byte1Shift = 16
private const val Byte2Shift = 8
private const val ByteMask = 0xff
private const val IvLenByteIndex3 = 3

/**
 * Stores and retrieves VPN profile blobs encrypted with AES-256-GCM.
 *
 * Each write generates a fresh 12-byte IV. The on-disk format is:
 *   [4-byte big-endian IV length][IV bytes][ciphertext bytes]
 *
 * The key is supplied by [keyManager]. For unit tests pass a [FakeKeystoreKeyManager].
 */
class EncryptedProfileStore(
    private val storageDir: File,
    private val keyManager: KeystoreKeyManager,
) {
    /**
     * Encrypts [plaintext] and writes it to [storageDir]/[profileId].enc.
     * Returns the ciphertext bytes (IV prepended) written to disk.
     */
    fun write(
        profileId: String,
        plaintext: ByteArray,
    ): ByteArray {
        val key = keyManager.getOrCreateKey()
        val blob = encrypt(key, plaintext)
        storageDir.mkdirs()
        blobFile(profileId).writeBytes(blob)
        return blob
    }

    /**
     * Reads and decrypts the blob for [profileId].
     * Returns null when no file exists for the given ID.
     */
    fun read(profileId: String): ByteArray? {
        val file = blobFile(profileId)
        if (!file.exists()) return null
        val blob = file.readBytes()
        val key = keyManager.getOrCreateKey()
        return decrypt(key, blob)
    }

    /** Deletes the encrypted blob for [profileId]. */
    fun delete(profileId: String) {
        blobFile(profileId).delete()
    }

    /** Returns true when an encrypted blob exists for [profileId]. */
    fun contains(profileId: String): Boolean = blobFile(profileId).exists()

    private fun blobFile(profileId: String): File = File(storageDir, "$profileId.enc")

    private fun encrypt(
        key: SecretKey,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(AesGcmTransformation)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return buildBlob(iv, ciphertext)
    }

    private fun buildBlob(
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val ivLen = iv.size
        val result = ByteArray(IvLenHeaderBytes + ivLen + ciphertext.size)
        result[0] = (ivLen shr Byte0Shift and ByteMask).toByte()
        result[1] = (ivLen shr Byte1Shift and ByteMask).toByte()
        result[2] = (ivLen shr Byte2Shift and ByteMask).toByte()
        result[IvLenByteIndex3] = (ivLen and ByteMask).toByte()
        System.arraycopy(iv, 0, result, IvLenHeaderBytes, ivLen)
        System.arraycopy(ciphertext, 0, result, IvLenHeaderBytes + ivLen, ciphertext.size)
        return result
    }

    private fun decrypt(
        key: SecretKey,
        blob: ByteArray,
    ): ByteArray {
        val ivLen = readIvLen(blob)
        val iv = blob.copyOfRange(IvLenHeaderBytes, IvLenHeaderBytes + ivLen)
        val ciphertext = blob.copyOfRange(IvLenHeaderBytes + ivLen, blob.size)
        val spec = GCMParameterSpec(GcmTagLenBits, iv)
        val cipher = Cipher.getInstance(AesGcmTransformation)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(ciphertext)
    }

    private fun readIvLen(blob: ByteArray): Int =
        (blob[0].toInt() and ByteMask shl Byte0Shift) or
            (blob[1].toInt() and ByteMask shl Byte1Shift) or
            (blob[2].toInt() and ByteMask shl Byte2Shift) or
            (blob[IvLenByteIndex3].toInt() and ByteMask)
}
