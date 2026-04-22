package com.poyka.ripdpi.strategy

import com.poyka.ripdpi.data.StrategyPackManifest
import com.poyka.ripdpi.data.StrategyPackSignatureAlgorithmSha256WithEcdsa
import com.poyka.ripdpi.security.AppTrustedSigningKeyResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.security.Signature
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

interface StrategyPackVerifier {
    @Throws(StrategyPackRefreshException::class)
    fun verify(
        manifest: StrategyPackManifest,
        payload: ByteArray,
        actualChecksumSha256: String,
    )
}

@Singleton
class DefaultStrategyPackVerifier
    @Inject
    constructor(
        private val keyResolver: AppTrustedSigningKeyResolver,
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

            val publicKey =
                keyResolver.resolveOrNull(manifest.keyId)
                    ?: throw StrategyPackUnknownSigningKeyException(manifest.keyId)
            signature.initVerify(publicKey)
            signature.update(payload)
            val valid =
                runCatching { signature.verify(signatureBytes) }.getOrElse {
                    throw StrategyPackSignatureMismatchException()
                }
            if (!valid) {
                throw StrategyPackSignatureMismatchException()
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class StrategyPackVerifierBindingsModule {
    @Binds
    @Singleton
    abstract fun bindStrategyPackVerifier(verifier: DefaultStrategyPackVerifier): StrategyPackVerifier
}
