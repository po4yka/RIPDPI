package com.poyka.ripdpi.keystore

import android.annotation.TargetApi
import android.os.Build
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

private const val AesKeySizeBits = 256

/**
 * Manages an AES-256-GCM key used to encrypt VPN profile blobs.
 *
 * In production the key is stored in the Android Keystore under [keystoreAlias].
 * When [useStrongBox] is true and the device supports StrongBox, the key is
 * generated inside the StrongBox security chip; otherwise it falls back to the
 * TEE-backed Keystore.
 *
 * Unit tests supply a [FakeKeystoreKeyManager] that stores the key in-memory so
 * no real Keystore is required.
 */
interface KeystoreKeyManager {
    /**
     * Returns true when StrongBox-backed key generation was requested and succeeded.
     * Returns false when StrongBox was unavailable and the key lives in the TEE.
     */
    val isStrongBoxBacked: Boolean

    /** Returns (or lazily creates) the encryption key. */
    fun getOrCreateKey(): SecretKey

    /** Deletes the key from the backing store. Used during migration cleanup. */
    fun deleteKey()
}

/**
 * In-memory implementation for JVM unit tests.
 *
 * Key material is stored as a plain [SecretKey] in the JVM heap — no Android
 * Keystore interaction. [isStrongBoxBacked] reflects the constructor flag so
 * StrongBox-preference logic can be exercised in tests.
 */
class FakeKeystoreKeyManager(
    override val isStrongBoxBacked: Boolean = false,
) : KeystoreKeyManager {
    private var cachedKey: SecretKey? = null

    override fun getOrCreateKey(): SecretKey {
        val existing = cachedKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance("AES")
        generator.init(AesKeySizeBits)
        val newKey = generator.generateKey()
        cachedKey = newKey
        return newKey
    }

    override fun deleteKey() {
        cachedKey = null
    }
}

/**
 * StrongBox-aware implementation that prefers StrongBox when [preferStrongBox]
 * is true, falls back to TEE on [StrongBoxUnavailableException].
 *
 * This class depends on Android framework APIs ([android.security.keystore.KeyGenParameterSpec])
 * and must not be instantiated in JVM unit tests; use [FakeKeystoreKeyManager] instead.
 */
class AndroidKeystoreKeyManager(
    private val keystoreAlias: String = DefaultKeystoreAlias,
    private val preferStrongBox: Boolean = true,
) : KeystoreKeyManager {
    override var isStrongBoxBacked: Boolean = false
        private set

    override fun getOrCreateKey(): SecretKey {
        val keystore = java.security.KeyStore.getInstance(AndroidKeystore)
        keystore.load(null)
        if (keystore.containsAlias(keystoreAlias)) {
            return (keystore.getEntry(keystoreAlias, null) as java.security.KeyStore.SecretKeyEntry).secretKey
        }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { generateKeyWithStrongBox() }.onSuccess { return it }
        }
        return generateKeyTee()
    }

    private fun generateKeyWithStrongBox(): SecretKey {
        val spec = buildStrongBoxKeyGenSpec()
        val generator = javax.crypto.KeyGenerator.getInstance("AES", AndroidKeystore)
        generator.init(spec)
        isStrongBoxBacked = true
        return generator.generateKey()
    }

    private fun generateKeyTee(): SecretKey {
        val spec = buildKeyGenSpec().build()
        val generator = javax.crypto.KeyGenerator.getInstance("AES", AndroidKeystore)
        generator.init(spec)
        isStrongBoxBacked = false
        return generator.generateKey()
    }

    @TargetApi(Build.VERSION_CODES.P)
    private fun buildStrongBoxKeyGenSpec(): android.security.keystore.KeyGenParameterSpec =
        buildKeyGenSpec().setIsStrongBoxBacked(true).build()

    private fun buildKeyGenSpec(): android.security.keystore.KeyGenParameterSpec.Builder =
        android.security.keystore.KeyGenParameterSpec
            .Builder(
                keystoreAlias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AesKeySizeBits)

    override fun deleteKey() {
        val keystore = java.security.KeyStore.getInstance(AndroidKeystore)
        keystore.load(null)
        if (keystore.containsAlias(keystoreAlias)) {
            keystore.deleteEntry(keystoreAlias)
        }
        isStrongBoxBacked = false
    }

    private companion object {
        const val AndroidKeystore = "AndroidKeyStore"
    }
}

internal const val DefaultKeystoreAlias = "ripdpi_profile_kek_v1"

/** Constructs a raw [SecretKey] from bytes; used in migration helpers. */
internal fun secretKeyFromBytes(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, "AES")
