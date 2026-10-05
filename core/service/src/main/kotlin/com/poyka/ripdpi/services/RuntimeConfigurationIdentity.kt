package com.poyka.ripdpi.services

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** Process-local identity. No raw canonical material or reusable digest leaves service ownership. */
internal class RuntimeConfigurationFacetIdentity(
    private val digest: ByteArray,
) {
    fun matches(other: RuntimeConfigurationFacetIdentity): Boolean = MessageDigest.isEqual(digest, other.digest)

    override fun toString(): String = "RuntimeConfigurationFacetIdentity([REDACTED])"
}

internal class RuntimeConfigurationIdentity(
    val transport: RuntimeConfigurationFacetIdentity,
    val dns: RuntimeConfigurationFacetIdentity,
) {
    fun matches(other: RuntimeConfigurationIdentity): Boolean {
        val transportMatches = transport.matches(other.transport)
        val dnsMatches = dns.matches(other.dns)
        return transportMatches and dnsMatches
    }

    fun withDns(other: RuntimeConfigurationIdentity): RuntimeConfigurationIdentity =
        RuntimeConfigurationIdentity(transport, other.dns)

    override fun toString(): String = "RuntimeConfigurationIdentity([REDACTED])"
}

@Singleton
internal class RuntimeConfigurationIdentityFactory private constructor(
    private val key: ByteArray,
) {
    @Inject
    constructor() : this(ByteArray(IdentityKeyBytes).also(SecureRandom()::nextBytes))

    fun capture(
        transport: List<String>,
        dns: List<String>,
    ): RuntimeConfigurationIdentity = RuntimeConfigurationIdentity(facet("transport", transport), facet("dns", dns))

    private fun facet(
        domain: String,
        material: List<String>,
    ): RuntimeConfigurationFacetIdentity {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (part in listOf("ripdpi-requested-config-v1", domain) + material) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            mac.update(bytes)
            bytes.fill(0)
        }
        return RuntimeConfigurationFacetIdentity(mac.doFinal())
    }
}

private const val IdentityKeyBytes = 32
