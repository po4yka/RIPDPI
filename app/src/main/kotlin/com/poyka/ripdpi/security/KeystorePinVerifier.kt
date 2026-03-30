package com.poyka.ripdpi.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeystorePinVerifier
    @Inject
    constructor() : PinVerifier {
        override fun hashPin(pin: String): String {
            val key = getOrCreateKey()
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(key)
            return mac
                .doFinal(pin.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        override fun verify(
            candidatePin: String,
            storedHash: String,
        ): Boolean {
            if (storedHash.isBlank()) return false
            if (!isKeyAvailable()) return false
            return MessageDigest.isEqual(
                hashPin(candidatePin).toByteArray(Charsets.UTF_8),
                storedHash.toByteArray(Charsets.UTF_8),
            )
        }

        override fun isKeyAvailable(): Boolean =
            try {
                val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
                ks.load(null)
                ks.containsAlias(KEY_ALIAS)
            } catch (_: Exception) {
                false
            }

        private fun getOrCreateKey(): SecretKey {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
            ks.load(null)
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGen = KeyGenerator.getInstance(HMAC_ALGORITHM, KEYSTORE_PROVIDER)
            keyGen.init(
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .build(),
            )
            return keyGen.generateKey()
        }

        private companion object {
            const val KEY_ALIAS = "ripdpi_pin_key"
            const val KEYSTORE_PROVIDER = "AndroidKeyStore"
            const val HMAC_ALGORITHM = "HmacSHA256"
        }
    }
