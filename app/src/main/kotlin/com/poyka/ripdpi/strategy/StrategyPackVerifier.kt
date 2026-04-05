package com.poyka.ripdpi.strategy

import com.poyka.ripdpi.data.DefaultStrategyPackSigningKeyId
import com.poyka.ripdpi.data.StrategyPackManifest
import com.poyka.ripdpi.data.StrategyPackSignatureAlgorithmSha256WithEcdsa
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

open class StrategyPackRefreshException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class StrategyPackChecksumFormatException :
    StrategyPackRefreshException("The strategy pack manifest checksum was not a valid SHA-256 digest.")

class StrategyPackChecksumMismatchException(
    val expected: String,
    val actual: String,
) : StrategyPackRefreshException("The downloaded strategy pack catalog checksum did not match the manifest.")

class StrategyPackSignatureMismatchException :
    StrategyPackRefreshException("The downloaded strategy pack catalog signature did not match the trusted key.")

class StrategyPackUnsupportedSignatureAlgorithmException(
    algorithm: String,
) : StrategyPackRefreshException("Unsupported strategy pack signature algorithm: $algorithm")

class StrategyPackUnknownSigningKeyException(
    keyId: String,
) : StrategyPackRefreshException("Unknown strategy pack signing key: $keyId")

class StrategyPackManifestParseException(
    cause: Throwable,
) : StrategyPackRefreshException("The strategy pack manifest could not be parsed.", cause)

class StrategyPackCatalogParseException(
    cause: Throwable,
) : StrategyPackRefreshException("The verified strategy pack catalog could not be parsed.", cause)

class StrategyPackCompatibilityException(
    reason: String,
) : StrategyPackRefreshException(reason)

fun interface StrategyPackPublicKeyResolver {
    @Throws(StrategyPackUnknownSigningKeyException::class)
    fun resolve(keyId: String): PublicKey
}

interface StrategyPackVerifier {
    @Throws(StrategyPackRefreshException::class)
    fun verify(
        manifest: StrategyPackManifest,
        payload: ByteArray,
        actualChecksumSha256: String,
    )
}

@Singleton
class DefaultStrategyPackPublicKeyResolver
    @Inject
    constructor() : StrategyPackPublicKeyResolver {
        private val keyFactory = KeyFactory.getInstance("EC")
        private val trustedKeys =
            mapOf(
                DefaultStrategyPackSigningKeyId to loadPublicKey(strategyPackTrustedP256PublicKeyPem),
            )

        override fun resolve(keyId: String): PublicKey =
            trustedKeys[keyId] ?: throw StrategyPackUnknownSigningKeyException(keyId)

        private fun loadPublicKey(pem: String): PublicKey {
            val der =
                Base64
                    .getMimeDecoder()
                    .decode(
                        pem
                            .replace("-----BEGIN PUBLIC KEY-----", "")
                            .replace("-----END PUBLIC KEY-----", "")
                            .trim(),
                    )
            return keyFactory.generatePublic(X509EncodedKeySpec(der))
        }
    }

@Singleton
class DefaultStrategyPackVerifier
    @Inject
    constructor(
        private val keyResolver: StrategyPackPublicKeyResolver,
    ) : StrategyPackVerifier {
        override fun verify(
            manifest: StrategyPackManifest,
            payload: ByteArray,
            actualChecksumSha256: String,
        ) {
            val expectedChecksum = manifest.catalogChecksumSha256.lowercase()
            if (!expectedChecksum.matches(Regex("[0-9a-f]{64}"))) {
                throw StrategyPackChecksumFormatException()
            }
            if (actualChecksumSha256.lowercase() != expectedChecksum) {
                throw StrategyPackChecksumMismatchException(expectedChecksum, actualChecksumSha256.lowercase())
            }

            val algorithm =
                manifest.signatureAlgorithm
                    .trim()
                    .ifBlank { StrategyPackSignatureAlgorithmSha256WithEcdsa }
            if (algorithm != StrategyPackSignatureAlgorithmSha256WithEcdsa) {
                throw StrategyPackUnsupportedSignatureAlgorithmException(algorithm)
            }

            val signature =
                runCatching {
                    Signature.getInstance(algorithm)
                }.getOrElse {
                    throw StrategyPackUnsupportedSignatureAlgorithmException(algorithm)
                }
            val signatureBytes =
                runCatching {
                    Base64.getDecoder().decode(manifest.catalogSignatureBase64)
                }.getOrElse {
                    throw StrategyPackSignatureMismatchException()
                }

            signature.initVerify(keyResolver.resolve(manifest.keyId))
            signature.update(payload)
            if (!signature.verify(signatureBytes)) {
                throw StrategyPackSignatureMismatchException()
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class StrategyPackVerifierBindingsModule {
    @Binds
    @Singleton
    abstract fun bindStrategyPackPublicKeyResolver(
        resolver: DefaultStrategyPackPublicKeyResolver,
    ): StrategyPackPublicKeyResolver

    @Binds
    @Singleton
    abstract fun bindStrategyPackVerifier(verifier: DefaultStrategyPackVerifier): StrategyPackVerifier
}

private const val strategyPackTrustedP256PublicKeyPem =
    """
    -----BEGIN PUBLIC KEY-----
    MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE9+0bf9ytpyS3fg6NTQUlPwE9GgPm
    ijz0iL+gYFZtyyajtXyvSP+8IcikchvLOwWcGrhalKQH4Qi7/CrWiz84Zg==
    -----END PUBLIC KEY-----
    """
