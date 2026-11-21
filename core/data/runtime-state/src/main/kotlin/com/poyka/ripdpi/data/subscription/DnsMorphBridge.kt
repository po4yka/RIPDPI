package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.serialization.RipDpiLenientJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The optional `ripdpi.dns_morph` delivery fallback for schema version 1. When every HTTPS endpoint of a
 * refresh fails, the app fetches the subscription body from this bridge over UDP and accepts it only
 * with a valid Ed25519 signature from [publicKey]. The latest accepted bundle decides: no block, no channel.
 * Wire contract: `docs/server-integration.md` ("DNS-Morph bootstrap channel").
 */
@Serializable
data class DnsMorphBridge(
    /** IP literal; the channel never resolves a name through plaintext DNS. */
    val address: String,
    val port: Int,
    val zone: String,
    /** Standard base64 of the bridge's 32-byte Ed25519 public key. */
    val publicKey: String,
    /** Standard base64 of this subscriber's 32-byte secret; it authorizes queries and keys the payload. */
    val token: String,
) {
    fun isValid(): Boolean =
        NumericIp.parseAddress(address)?.let { !it.isZero && !it.isMulticast } == true &&
            port in 1..MaxPort &&
            zone.length <= MaxZoneLength &&
            zone.split('.').all { it.matches(ZoneLabel) } &&
            isKey(publicKey) &&
            isKey(token)

    override fun toString(): String = "DnsMorphBridge([REDACTED])"

    // Canonical padded form only, the same form the native client accepts.
    private fun isKey(encoded: String): Boolean =
        runCatching { Base64.getDecoder().decode(encoded) }
            .getOrNull()
            ?.takeIf { it.size == KeyBytes && Base64.getEncoder().encodeToString(it) == encoded } != null
}

/** Fetches the verified payload from [bridge]; throws an [java.io.IOException] on any failure. */
fun interface DnsMorphFetcher {
    suspend fun fetch(bridge: DnsMorphBridge): ByteArray
}

/**
 * Reads `ripdpi.dns_morph` from an accepted bundle body. A body that is not a versioned RIPDPI bundle
 * declares no channel; an invalid declaration throws [IllegalArgumentException] and rejects the bundle.
 */
fun parseBundleDnsMorph(body: String): DnsMorphBridge? {
    // Same reader and version semantics as SingBoxSubscriptionParser, so a bundle it accepts keeps its channel.
    val root = runCatching { RipDpiLenientJson.parseToJsonElement(body) }.getOrNull() as? JsonObject
    val block = root?.get("ripdpi") as? JsonObject
    val version =
        (block?.get("schema_version") as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    return if (block != null && version == SingBoxSubscriptionParser.RIPDPI_SCHEMA_VERSION) {
        parseDnsMorphBridge(block)
    } else {
        null
    }
}

/**
 * Fetches the verified bridge payload and parses it with [parse]. Any failure, including a rejected
 * bundle, returns `null`, so the channel never replaces the HTTPS refresh result.
 */
suspend fun <T : Any> DnsMorphFetcher.deliver(
    bridge: DnsMorphBridge,
    parse: suspend (SubscriptionPayload) -> T?,
): T? {
    val payload = fetchSubscriptionPayload(bridge) ?: return null
    return try {
        parse(payload)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private suspend fun DnsMorphFetcher.fetchSubscriptionPayload(bridge: DnsMorphBridge): SubscriptionPayload? {
    if (!bridge.isValid()) return null
    return try {
        SubscriptionPayload(fetch(bridge), StandardCharsets.UTF_8)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (
        @Suppress("TooGenericExceptionCaught") _: Exception,
    ) {
        // Native, decoding or loader failures all leave the HTTPS result in place.
        null
    }
}

internal fun parseDnsMorphBridge(block: JsonObject): DnsMorphBridge? {
    val element = block["dns_morph"] ?: return null
    val entry = requireNotNull(element as? JsonObject) { "invalid DNS-Morph bridge" }
    require(entry.keys == setOf("address", "port", "zone", "public_key", "token")) { "invalid DNS-Morph bridge fields" }

    fun string(key: String) =
        requireNotNull(
            (entry[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull,
        ) { "invalid DNS-Morph bridge" }
    val port =
        requireNotNull(
            (entry["port"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull,
        ) { "invalid DNS-Morph port" }
    val bridge =
        DnsMorphBridge(string("address"), port, string("zone").lowercase(), string("public_key"), string("token"))
    require(bridge.isValid()) { "invalid DNS-Morph bridge" }
    return bridge
}

private const val MaxPort = 65_535

// Keeps an uncompressed reply under the 512-byte datagram cap; equals the Rust limit.
private const val MaxZoneLength = 54
private const val KeyBytes = 32
private val ZoneLabel = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
