package com.poyka.ripdpi.data.testing

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator

/** Supplies test keys for real legacy AES-GCM preferences; does not model Android key protection. */
class LegacyProfileKeyStore : Closeable {
    private val provider =
        object : Provider("AndroidKeyStore", 1.0, "Legacy profile test keys") {
            init {
                put("KeyStore.AndroidKeyStore", LegacyProfileKeyStoreSpi::class.java.name)
            }
        }

    init {
        check(Security.getProvider(provider.name) == null) { "A key provider is already installed" }
        check(Security.addProvider(provider) != -1)
    }

    override fun close() {
        Security.removeProvider(provider.name)
        LegacyProfileKeyStoreSpi.keys.clear()
    }
}

/** Public so JCA can construct the provider service. */
class LegacyProfileKeyStoreSpi : KeyStoreSpi() {
    override fun engineGetKey(
        alias: String,
        password: CharArray?,
    ): Key =
        keys.computeIfAbsent(alias) {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }

    override fun engineLoad(
        stream: InputStream?,
        password: CharArray?,
    ) = Unit

    override fun engineStore(
        stream: OutputStream?,
        password: CharArray?,
    ) = error("Test keys are not exported")

    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

    override fun engineGetCertificate(alias: String): Certificate? = null

    override fun engineGetCreationDate(alias: String): Date? = if (keys.containsKey(alias)) Date(0L) else null

    override fun engineSetKeyEntry(
        alias: String,
        key: Key,
        password: CharArray?,
        chain: Array<Certificate>?,
    ) {
        keys[alias] = key
    }

    override fun engineSetKeyEntry(
        alias: String,
        key: ByteArray,
        chain: Array<Certificate>?,
    ) = error("Unsupported key format")

    override fun engineSetCertificateEntry(
        alias: String,
        certificate: Certificate,
    ) = error("No test certificates")

    override fun engineDeleteEntry(alias: String) {
        keys.remove(alias)
    }

    override fun engineAliases() = Collections.enumeration(keys.keys)

    override fun engineContainsAlias(alias: String) = keys.containsKey(alias)

    override fun engineSize() = keys.size

    override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)

    override fun engineIsCertificateEntry(alias: String) = false

    override fun engineGetCertificateAlias(certificate: Certificate): String? = null

    internal companion object {
        val keys = ConcurrentHashMap<String, Key>()
    }
}
