package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.NumericIp
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class Tcp16Transport(
    val scheme: String,
    val defaultPort: Int,
) {
    HTTP("http", 80),
    HTTPS("https", 443),
    ;

    companion object {
        fun forDefaultPort(port: Int): Tcp16Transport? = entries.firstOrNull { it.defaultPort == port }

        fun fromScheme(scheme: String): Tcp16Transport? = entries.firstOrNull { it.scheme == scheme }
    }
}

data class Tcp16Target(
    val id: String,
    val asn: String,
    val provider: String,
    val ip: String,
    val port: Int,
    val sni: String?,
    val transport: Tcp16Transport? = Tcp16Transport.forDefaultPort(port),
    internal val httpAuthority: String? = null,
) {
    internal fun invalidReason(): String? =
        when {
            listOf(
                id,
                asn,
                provider,
            ).any { it.isBlank() || it.length > MaxMetadataLength } -> "Invalid TCP16 catalog metadata"

            NumericIp.parseAddress(ip) == null -> "TCP16 requires a numeric IP address"

            port !in 1..65_535 -> "Invalid TCP16 port"

            sni != null && !sni.isTcp16ServerName() -> "Invalid TCP16 TLS server name"

            httpAuthority != null && !isValidAuthority(httpAuthority) -> "Invalid TCP16 HTTP authority"

            else -> null
        }

    internal fun url(host: String): HttpUrl =
        HttpUrl
            .Builder()
            .scheme(requireNotNull(transport).scheme)
            .host(host)
            .port(port)
            .build()

    private fun isValidAuthority(authority: String): Boolean {
        val scheme = transport?.scheme ?: return false
        val url = "$scheme://$authority".toHttpUrlOrNull() ?: return false
        return url.toUri().rawAuthority == authority && url.port == port && url.encodedUsername.isEmpty()
    }

    private companion object {
        private const val MaxMetadataLength = 128
    }
}

internal fun String.isTcp16ServerName(): Boolean =
    length in 1..MaxDnsNameLength && NumericIp.parseAddress(this) == null && split('.').all(DnsLabel::matches)

private const val MaxDnsNameLength = 253
private val DnsLabel = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
