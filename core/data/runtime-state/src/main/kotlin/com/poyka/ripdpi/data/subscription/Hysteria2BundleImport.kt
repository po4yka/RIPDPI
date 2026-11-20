package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.Hysteria2Realm
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.hysteria2RealmEndpoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Realm settings of a hysteria2 outbound; the rendezvous URL host and port stand in for its absent server. */
internal class Hysteria2RealmImport(
    val realm: Hysteria2Realm,
    val token: String?,
    val host: String,
    val port: Int,
)

internal fun hysteria2RealmImport(outbound: JsonObject): Hysteria2RealmImport? {
    val block = outbound["realm"] as? JsonObject ?: return null
    val endpoint =
        requireNotNull(hysteria2RealmEndpoint(block.text("server_url").orEmpty())) {
            "Hysteria realm server_url must be an http or https URL"
        }
    // The token is a secret: keep it as written, like the passwords.
    val token = (block["token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    return Hysteria2RealmImport(hysteria2Realm(block), token, endpoint.first, endpoint.second)
}

/** Upstream forbids a fixed address, port and port hopping next to a realm. */
internal fun hysteria2RealmHasServer(outbound: JsonObject): Boolean =
    outbound["realm"] is JsonObject && listOf("server", "server_port", "server_ports").any(outbound::containsKey)

/** An empty realm id here is filled from `ripdpi.topology.hysteria_realm`, or rejected, after the bundle is read. */
private fun hysteria2Realm(block: JsonObject): Hysteria2Realm {
    val stunServers =
        when (val stun = block["stun_servers"]) {
            is JsonArray -> {
                stun.mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(
                        String::isNotEmpty,
                    )
                }
            }

            is JsonPrimitive -> {
                listOfNotNull(stun.contentOrNull?.trim()?.takeIf(String::isNotEmpty))
            }

            else -> {
                emptyList()
            }
        }
    require(stunServers.isNotEmpty()) { "Hysteria realm requires at least one STUN server" }
    return Hysteria2Realm(
        realmId = block.text("realm_id").orEmpty(),
        serverUrl = block.text("server_url").orEmpty().trim(),
        stunServers = stunServers,
    )
}

internal fun isRealmWithoutId(profile: ProxyProfile): Boolean =
    profile is ProxyProfile.Hysteria2 &&
        profile.realm?.let { it.serverUrl.isNotEmpty() && it.realmId.isEmpty() } == true

/**
 * Gives every Hysteria2 profile the bundle-wide realm id. A profile that carries its own realm block keeps
 * its endpoint and must agree on the id; a direct profile only records the id and keeps its address.
 */
internal fun applyRealmTopology(
    profiles: List<ProxyProfile>,
    topologyRealmId: String?,
): List<ProxyProfile> {
    if (topologyRealmId == null) return profiles
    return profiles.map { profile ->
        if (profile !is ProxyProfile.Hysteria2) return@map profile
        val realm = profile.realm
        when {
            realm == null -> {
                profile.copy(realm = Hysteria2Realm(realmId = topologyRealmId))
            }

            realm.realmId.isEmpty() -> {
                profile.copy(realm = realm.copy(realmId = topologyRealmId))
            }

            else -> {
                require(realm.realmId == topologyRealmId) { "Hysteria realm id conflicts with the bundle topology" }
                profile
            }
        }
    }
}

/**
 * Patches already-parsed [ProxyProfile.Hysteria2] entries with extras from
 * `ripdpi.hysteria_extras`. Matching is by [ProxyProfile.displayName] (which
 * is set from the outbound's `tag` during parsing).
 */
internal fun applyHysteriaExtras(
    profiles: List<ProxyProfile>,
    hyExtras: JsonObject,
): List<ProxyProfile> {
    if (hyExtras.isEmpty()) return profiles
    hyExtras.forEach { (tag, element) ->
        val extras = requireNotNull(element as? JsonObject) { "Hysteria extras must be an object" }
        if ("port_hopping" in extras) {
            bundleHysteria2PortHopping(extras)
            require(profiles.count { it is ProxyProfile.Hysteria2 && it.displayName == tag } == 1) {
                "Hysteria hopping metadata has no unique supported outbound"
            }
        }
    }
    return profiles.map { profile ->
        if (profile !is ProxyProfile.Hysteria2) return@map profile
        val extras = hyExtras[profile.displayName] as? JsonObject ?: return@map profile
        val obfsObj = extras["obfs"] as? JsonObject
        val obfsType = obfsObj?.text("type")?.lowercase()
        val obfsPassword = if (obfsType == "salamander") obfsObj.text("password") else null
        val insecure = (extras["insecure"] as? JsonPrimitive)?.booleanOrNull
        val hopping = bundleHysteria2PortHopping(extras)
        require(hopping == null || profile.portHopping == null || hopping == profile.portHopping) {
            "Hysteria hopping metadata conflicts with the outbound"
        }
        val salamanderUpstreamTag = extras.text("salamander_upstream_tag")
        profile.copy(
            obfsPassword = obfsPassword ?: profile.obfsPassword,
            insecure = insecure ?: profile.insecure,
            portHopping = hopping ?: profile.portHopping,
            salamanderUpstreamTag = salamanderUpstreamTag ?: profile.salamanderUpstreamTag,
        )
    }
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf {
        it.isNotBlank()
    }
