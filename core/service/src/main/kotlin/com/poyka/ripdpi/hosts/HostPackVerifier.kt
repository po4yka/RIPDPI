package com.poyka.ripdpi.hosts

import com.poyka.ripdpi.data.HostPackManifest
import com.poyka.ripdpi.data.StrategyPackSignatureAlgorithmSha256WithEcdsa
import com.poyka.ripdpi.security.AppTrustedSigningKeyResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.security.Signature
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

class HostPackSignatureMismatchException :
    HostPackRefreshException("The downloaded host pack catalog signature did not match the trusted key.")

class HostPackUnsupportedSignatureAlgorithmException(
    algorithm: String,
) : HostPackRefreshException("Unsupported host pack signature algorithm: $algorithm")

class HostPackUnknownSigningKeyException(
    keyId: String,
) : HostPackRefreshException("Unknown host pack signing key: $keyId")

class HostPackManifestParseException(
    cause: Throwable,
) : HostPackRefreshException("The host pack manifest could not be parsed.", cause)

interface HostPackVerifier {
    @Throws(HostPackRefreshException::class)
    fun verify(
        manifest: HostPackManifest,
        payload: ByteArray,
        actualChecksumSha256: String,
    )
}

@Singleton
class DefaultHostPackVerifier
    @Inject
    constructor(
        private val keyResolver: AppTrustedSigningKeyResolver,
    ) : HostPackVerifier {
        override fun verify(
            manifest: HostPackManifest,
            payload: ByteArray,
            actualChecksumSha256: String,
        ) {
            val expectedChecksum = manifest.catalogChecksumSha256.trim().lowercase()
            if (!expectedChecksum.matches(Regex("[0-9a-f]{64}"))) {
                throw HostPackChecksumFormatException()
            }
            if (actualChecksumSha256.lowercase() != expectedChecksum) {
                throw HostPackChecksumMismatchException(expectedChecksum, actualChecksumSha256.lowercase())
            }

            val algorithm =
                manifest.signatureAlgorithm
                    .trim()
                    .ifBlank { StrategyPackSignatureAlgorithmSha256WithEcdsa }
            if (algorithm != StrategyPackSignatureAlgorithmSha256WithEcdsa) {
                throw HostPackUnsupportedSignatureAlgorithmException(algorithm)
            }

            val signature =
                runCatching {
                    Signature.getInstance(algorithm)
                }.getOrElse {
                    throw HostPackUnsupportedSignatureAlgorithmException(algorithm)
                }
            val signatureBytes =
                runCatching {
                    Base64.getDecoder().decode(manifest.catalogSignatureBase64)
                }.getOrElse {
                    throw HostPackSignatureMismatchException()
                }

            val publicKey =
                keyResolver.resolveOrNull(manifest.keyId)
                    ?: throw HostPackUnknownSigningKeyException(manifest.keyId)
            signature.initVerify(publicKey)
            signature.update(payload)
            val valid =
                runCatching { signature.verify(signatureBytes) }.getOrElse {
                    throw HostPackSignatureMismatchException()
                }
            if (!valid) {
                throw HostPackSignatureMismatchException()
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class HostPackVerifierBindingsModule {
    @Binds
    @Singleton
    abstract fun bindHostPackVerifier(verifier: DefaultHostPackVerifier): HostPackVerifier
}
