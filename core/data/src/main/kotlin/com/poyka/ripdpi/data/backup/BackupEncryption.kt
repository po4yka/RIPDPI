package com.poyka.ripdpi.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned, authenticated envelope used exclusively for credential-bearing FULL backups. */
object BackupEncryption {
    private val magic =
        byteArrayOf(
            'R'.code.toByte(),
            'I'.code.toByte(),
            'P'.code.toByte(),
            'D'.code.toByte(),
            'P'.code.toByte(),
            'I'.code.toByte(),
            'E'.code.toByte(),
            '1'.code.toByte(),
        )
    private const val SaltBytes = 16
    private const val NonceBytes = 12
    private const val KeyBits = 256
    private const val Iterations = 210_000
    private const val GcmTagBits = 128
    private const val HeaderBytes = 8 + Int.SIZE_BYTES + SaltBytes + NonceBytes

    fun isEncrypted(archive: ByteArray): Boolean =
        archive.size >= magic.size && archive.copyOfRange(0, magic.size).contentEquals(magic)

    fun encryptToStream(
        document: BackupV1,
        passphrase: CharArray,
        output: OutputStream,
        secureRandom: SecureRandom = SecureRandom(),
    ): Long {
        require(document.containsCredentials) { "Only FULL backups use the encrypted envelope" }
        require(passphrase.isNotEmpty()) { "A passphrase is required for FULL backups" }
        val salt = ByteArray(SaltBytes).also(secureRandom::nextBytes)
        val nonce = ByteArray(NonceBytes).also(secureRandom::nextBytes)
        val header =
            ByteArrayOutputStream(HeaderBytes).use { bytes ->
                DataOutputStream(bytes).use { data ->
                    data.write(magic)
                    data.writeInt(Iterations)
                    data.write(salt)
                    data.write(nonce)
                }
                bytes.toByteArray()
            }
        val counting = CountingArchiveOutputStream(output)
        counting.write(header)
        val keyBytes =
            try {
                deriveKeyBytes(passphrase, salt, Iterations)
            } catch (error: GeneralSecurityException) {
                throw IOException("Could not protect backup", error)
            }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GcmTagBits, nonce))
            cipher.updateAAD(header)
            CipherOutputStream(NonClosingOutputStream(counting), cipher).use { encrypted ->
                BackupSerializer.encodeToStream(document, encrypted)
            }
            counting.flush()
            return counting.bytesWritten
        } catch (error: GeneralSecurityException) {
            throw IOException("Could not protect backup", error)
        } finally {
            keyBytes.fill(0)
        }
    }

    @Throws(BackupDecryptionException::class)
    fun decryptToString(
        archive: ByteArray,
        passphrase: CharArray,
    ): String {
        val header = parseHeader(archive) ?: throw BackupDecryptionException()
        return runCatching { decryptAuthenticated(archive, passphrase, header) }
            .getOrElse(::mapDecryptionFailure)
    }

    private fun parseHeader(archive: ByteArray): EncryptionHeader? =
        runCatching {
            if (!isEncrypted(archive) || archive.size <= HeaderBytes) return null
            val bytes = ByteArray(HeaderBytes)
            DataInputStream(ByteArrayInputStream(archive)).use { it.readFully(bytes) }
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val readMagic = ByteArray(magic.size).also(input::readFully)
            val iterations = input.readInt()
            if (!readMagic.contentEquals(magic) || iterations != Iterations) return null
            EncryptionHeader(
                bytes = bytes,
                iterations = iterations,
                salt = ByteArray(SaltBytes).also(input::readFully),
                nonce = ByteArray(NonceBytes).also(input::readFully),
            )
        }.getOrNull()

    private fun decryptAuthenticated(
        archive: ByteArray,
        passphrase: CharArray,
        header: EncryptionHeader,
    ): String {
        val keyBytes = deriveKeyBytes(passphrase, header.salt, header.iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GcmTagBits, header.nonce))
            cipher.updateAAD(header.bytes)
            val plaintext = cipher.doFinal(archive, HeaderBytes, archive.size - HeaderBytes)
            return Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext))
                .toString()
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun mapDecryptionFailure(error: Throwable): Nothing {
        if (error is AEADBadTagException || error is GeneralSecurityException || error is IOException) {
            throw BackupDecryptionException()
        }
        throw error
    }

    private fun deriveKeyBytes(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KeyBits)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

private data class EncryptionHeader(
    val bytes: ByteArray,
    val iterations: Int,
    val salt: ByteArray,
    val nonce: ByteArray,
)

class BackupDecryptionException : Exception()

private class CountingArchiveOutputStream(
    delegate: OutputStream,
) : FilterOutputStream(delegate) {
    var bytesWritten: Long = 0
        private set

    override fun write(b: Int) {
        out.write(b)
        bytesWritten++
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        out.write(b, off, len)
        bytesWritten += len
    }

    override fun close() = out.flush()
}

private class NonClosingOutputStream(
    delegate: OutputStream,
) : FilterOutputStream(delegate) {
    override fun close() = out.flush()
}
