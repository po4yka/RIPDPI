package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.wireguard.AmneziaWgParameters
import com.poyka.ripdpi.data.wireguard.AmneziaWgProfile
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Codec for the locally-defined `amneziawg://` single-profile share URI.
 *
 * There is no standardized AmneziaWG share-URI scheme upstream — the reference
 * client shares `.conf` files (or a QR of a `.conf`). RIPDPI defines one here
 * for ergonomic single-profile sharing. The layout follows the Hysteria2 URI
 * shape (userinfo carries the secret, host:port is the endpoint, auxiliary
 * fields are query params, the display name is the fragment):
 *
 * ```
 * amneziawg://<private-key>@<host>:<port>
 *   ?public_key=<key>
 *   &preshared_key=<key>
 *   &allowed_ips=<cidr,cidr>
 *   &dns=<ip,ip>
 *   &mtu=<n>
 *   &jc=<n>&jmin=<n>&jmax=<n>&s1=<n>&s2=<n>&s3=0&s4=0
 *   &h1=<n>&h2=<n>&h3=<n>&h4=<n>
 *   &i1=<hex>&i2=<hex>&i3=<hex>&i4=<hex>&i5=<hex>
 *   #<name>
 * ```
 *
 * Key material (which contains `+`, `/`, `=`) is percent-encoded in the
 * userinfo and query positions, so the URI is always structurally valid and
 * the round-trip is lossless. [decode] never throws: an unrecognised scheme, a
 * structurally broken URI, or a missing mandatory field yields `null`; a
 * malformed *optional* numeric param is simply dropped. A profile with non-zero
 * `s3` or `s4` is rejected for Android arm64 safety (amneziawg-go#110).
 */
object AmneziaWgUriCodec {
    private const val SCHEME = "amneziawg"
    private const val SCHEME_PREFIX = "$SCHEME://"

    /** Encodes [profile] into an `amneziawg://` share URI. */
    fun encode(profile: AmneziaWgProfile): String {
        profile.awg.requireArm64Safe()
        val query =
            (baseParams(profile) + awgParams(profile.awg))
                .joinToString("&") { (key, value) -> "$key=${urlEncode(value)}" }
        return buildString {
            append(SCHEME_PREFIX)
            append(urlEncode(profile.privateKey))
            append('@')
            append(profile.host)
            append(':')
            append(profile.port)
            if (query.isNotEmpty()) {
                append('?')
                append(query)
            }
            append('#')
            append(urlEncode(profile.name))
        }
    }

    /** The non-obfuscation query params, in declared order, omitting absent fields. */
    private fun baseParams(profile: AmneziaWgProfile): List<Pair<String, String>> =
        buildList {
            add("public_key" to profile.publicKey)
            profile.presharedKey?.let { add("preshared_key" to it) }
            profile.allowedIps.takeIf { it.isNotEmpty() }?.let { add("allowed_ips" to it.joinToString(",")) }
            profile.dns.takeIf { it.isNotEmpty() }?.let { add("dns" to it.joinToString(",")) }
            profile.mtu?.let { add("mtu" to it.toString()) }
        }

    /** The AmneziaWG obfuscation query params, in declared order, omitting absent fields. */
    private fun awgParams(awg: AmneziaWgParameters): List<Pair<String, String>> =
        listOfNotNull(
            awg.jc?.let { "jc" to it.toString() },
            awg.jmin?.let { "jmin" to it.toString() },
            awg.jmax?.let { "jmax" to it.toString() },
            awg.s1?.let { "s1" to it.toString() },
            awg.s2?.let { "s2" to it.toString() },
            awg.s3?.let { "s3" to it.toString() },
            awg.s4?.let { "s4" to it.toString() },
            awg.h1?.let { "h1" to it.toString() },
            awg.h2?.let { "h2" to it.toString() },
            awg.h3?.let { "h3" to it.toString() },
            awg.h4?.let { "h4" to it.toString() },
            awg.i1?.let { "i1" to it },
            awg.i2?.let { "i2" to it },
            awg.i3?.let { "i3" to it },
            awg.i4?.let { "i4" to it },
            awg.i5?.let { "i5" to it },
        )

    /**
     * Decodes [uri] into an [AmneziaWgProfile], or returns `null` when the
     * scheme is not `amneziawg://`, the URI is structurally broken, or a
     * mandatory field (private key, public key, host, port) is missing.
     */
    fun decode(uri: String): AmneziaWgProfile? =
        extractMandatory(uri)?.let { mandatory ->
            val params = mandatory.params
            val awg =
                AmneziaWgParameters(
                    jc = params["jc"]?.toIntOrNull(),
                    jmin = params["jmin"]?.toIntOrNull(),
                    jmax = params["jmax"]?.toIntOrNull(),
                    s1 = params["s1"]?.toIntOrNull(),
                    s2 = params["s2"]?.toIntOrNull(),
                    s3 = params["s3"]?.toIntOrNull(),
                    s4 = params["s4"]?.toIntOrNull(),
                    h1 = params["h1"]?.toLongOrNull(),
                    h2 = params["h2"]?.toLongOrNull(),
                    h3 = params["h3"]?.toLongOrNull(),
                    h4 = params["h4"]?.toLongOrNull(),
                    i1 = params["i1"]?.takeIf { it.isNotBlank() },
                    i2 = params["i2"]?.takeIf { it.isNotBlank() },
                    i3 = params["i3"]?.takeIf { it.isNotBlank() },
                    i4 = params["i4"]?.takeIf { it.isNotBlank() },
                    i5 = params["i5"]?.takeIf { it.isNotBlank() },
                )
            runCatching {
                awg.requireArm64Safe()
                AmneziaWgProfile(
                    name = mandatory.name,
                    host = mandatory.host,
                    port = mandatory.port,
                    privateKey = mandatory.privateKey,
                    publicKey = mandatory.publicKey,
                    presharedKey = params["preshared_key"]?.takeIf { it.isNotBlank() },
                    allowedIps = splitList(params["allowed_ips"]),
                    dns = splitList(params["dns"]),
                    mtu = params["mtu"]?.toIntOrNull(),
                    awg = awg,
                )
            }.getOrNull()
        }

    /** The mandatory fields of an `amneziawg://` URI plus its decoded query map. */
    private data class MandatoryFields(
        val privateKey: String,
        val publicKey: String,
        val host: String,
        val port: Int,
        val name: String,
        val params: Map<String, String>,
    )

    /**
     * Parses [uri] as an `amneziawg://` URI and extracts its mandatory fields
     * (private key, public key, host, port) plus the display name and query
     * map. Returns `null` when the scheme is wrong, the URI is structurally
     * broken, or any mandatory field is absent — never throws.
     *
     * Guard-clause early returns are intentional here: one `?: return null` per
     * mandatory field reads far more clearly than a deep `let` nest, hence the
     * narrow `ReturnCount` suppression.
     */
    @Suppress("ReturnCount")
    private fun extractMandatory(uri: String): MandatoryFields? {
        val parsed =
            uri
                .trim()
                .takeIf { it.startsWith(SCHEME_PREFIX, ignoreCase = true) }
                ?.let { runCatching { URI(it) }.getOrNull() }
                ?: return null

        val privateKey = parsed.rawUserInfo?.takeIf { it.isNotBlank() }?.let { urlDecode(it) } ?: return null
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
        val port = parsed.port.takeIf { it > 0 } ?: return null
        val params = parseQuery(parsed.rawQuery)
        val publicKey = params["public_key"]?.takeIf { it.isNotBlank() } ?: return null

        return MandatoryFields(
            privateKey = privateKey,
            publicKey = publicKey,
            host = host,
            port = port,
            name = parsed.rawFragment?.takeIf { it.isNotBlank() }?.let { urlDecode(it) } ?: host,
            params = params,
        )
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        val query = rawQuery?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return query
            .split('&')
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    pair.substring(0, separator) to urlDecode(pair.substring(separator + 1))
                }
            }.toMap()
    }

    private fun splitList(raw: String?): List<String> =
        raw
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
}
