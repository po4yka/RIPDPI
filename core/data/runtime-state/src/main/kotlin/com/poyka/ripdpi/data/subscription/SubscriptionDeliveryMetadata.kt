package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Client-recognized optional `ripdpi.subscription_mirrors` extension for schema version 1.
 * Each entry has a unique `id`, HTTPS `url`, optional raw bearer `token`, and explicit
 * `transport` (`direct` or `cloudflare`). At most eight endpoints are accepted.
 * Credentials belong only to their declared endpoint, never to a redirect or another mirror.
 * An absent extension preserves saved endpoints; an empty array removes them.
 * The producer must explicitly supply this extension; clients do not discover mirrors.
 */
internal fun parseSubscriptionMirrors(block: JsonObject): SubscriptionMirrorSet? {
    val element = block["subscription_mirrors"] ?: return null
    val entries = element as? JsonArray ?: error("invalid subscription mirror metadata")
    require(entries.size <= MaxSubscriptionMirrors) { "too many subscription mirrors" }
    val mirrors =
        entries.map { entry ->
            val obj = entry as? JsonObject ?: error("invalid subscription mirror entry")
            val id = obj.requiredString("id")
            val url = obj.requiredString("url")
            val token = if ("token" in obj) obj.requiredString("token", allowEmpty = true) else ""
            val transport =
                when (obj.requiredString("transport")) {
                    "direct" -> SubscriptionMirrorTransport.DIRECT
                    "cloudflare" -> SubscriptionMirrorTransport.CLOUDFLARE
                    else -> error("invalid subscription mirror transport")
                }
            require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "invalid subscription mirror id" }
            require(isSecureSubscriptionEndpoint(url, token)) { "invalid subscription mirror endpoint" }
            SubscriptionMirror(id, url, token, transport)
        }
    require(mirrors.map { it.id }.distinct().size == mirrors.size) { "duplicate subscription mirror id" }
    require(mirrors.map { it.url.toHttpUrlOrNull() }.distinct().size == mirrors.size) {
        "duplicate subscription mirror endpoint"
    }
    return SubscriptionMirrorSet(mirrors)
}

/**
 * `ripdpi.cloudflare_outbound_tags` explicitly names Cloudflare-backed outbound tags.
 * Tags must each resolve to one concrete profile; names or transport types are never heuristics.
 * Absence preserves known classification on refresh; an explicit empty array clears it.
 */
internal fun parseCloudflareMemberIds(
    block: JsonObject,
    profiles: List<ProxyProfile>,
): Set<String>? {
    val element = block["cloudflare_outbound_tags"] ?: return null
    val entries = element as? JsonArray ?: error("invalid Cloudflare classification")
    require(entries.size <= MaxSubscriptionProfiles) { "too many Cloudflare tags" }
    val tags =
        entries.map { entry ->
            val primitive = entry as? JsonPrimitive
            primitive?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("invalid Cloudflare tag")
        }
    require(tags.distinct().size == tags.size) { "duplicate Cloudflare tag" }
    return tags.mapTo(linkedSetOf()) { tag ->
        profiles.singleOrNull { it.displayName == tag }?.id ?: error("unresolved Cloudflare tag")
    }
}

internal fun isSecureSubscriptionEndpoint(
    url: String,
    token: String,
): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null &&
        url.length <= MaxEndpointLength && token.length <= MaxTokenLength && token.all { it in '!'..'~' }
}

/** Revalidates persisted or restored delivery policy before a network request can use it. */
fun SubscriptionMirrorSet.isValidForRefresh(): Boolean =
    mirrors.size <= MaxSubscriptionMirrors &&
        mirrors.all { it.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && isSecureSubscriptionEndpoint(it.url, it.token) } &&
        mirrors.map { it.id }.distinct().size == mirrors.size &&
        mirrors.map { it.url.toHttpUrlOrNull() }.distinct().size == mirrors.size

private fun JsonObject.requiredString(
    key: String,
    allowEmpty: Boolean = false,
): String {
    val primitive = this[key] as? JsonPrimitive
    val value = primitive?.takeIf { it.isString }?.contentOrNull ?: error("invalid subscription mirror field")
    require(allowEmpty || value.isNotEmpty()) { "empty subscription mirror field" }
    return value
}

/** Maximum number of declared endpoints in one subscription refresh policy. */
const val MaxSubscriptionMirrors = 8
private const val MaxEndpointLength = 2048
private const val MaxTokenLength = 4096
