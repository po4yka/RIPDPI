package com.poyka.ripdpi.security

import com.poyka.ripdpi.data.DefaultStrategyPackSigningKeyId
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

fun interface AppTrustedSigningKeyResolver {
    fun resolveOrNull(keyId: String): PublicKey?
}

@Singleton
class DefaultAppTrustedSigningKeyResolver
    @Inject
    constructor() : AppTrustedSigningKeyResolver {
        private val keyFactory = KeyFactory.getInstance("EC")

        private val trustedKeys =
            mapOf(
                // Rotation: add the new public key under a new key id, ship an app version
                // that trusts both keys, start publishing manifests with the new key id,
                // then remove the old key only after supported app versions no longer
                // require it.
                DefaultStrategyPackSigningKeyId to loadPublicKey(strategyPackTrustedP256PublicKeyPem),
            )

        override fun resolveOrNull(keyId: String): PublicKey? = trustedKeys[keyId]

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

@Module
@InstallIn(SingletonComponent::class)
abstract class AppTrustedSigningKeyBindingsModule {
    @Binds
    @Singleton
    abstract fun bindAppTrustedSigningKeyResolver(
        resolver: DefaultAppTrustedSigningKeyResolver,
    ): AppTrustedSigningKeyResolver
}

private const val strategyPackTrustedP256PublicKeyPem =
    """
    -----BEGIN PUBLIC KEY-----
    MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE9+0bf9ytpyS3fg6NTQUlPwE9GgPm
    ijz0iL+gYFZtyyajtXyvSP+8IcikchvLOwWcGrhalKQH4Qi7/CrWiz84Zg==
    -----END PUBLIC KEY-----
    """
