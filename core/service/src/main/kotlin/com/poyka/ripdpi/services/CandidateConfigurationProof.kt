package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Process-local keyed identity of the exact candidate input. It cannot be serialized or exported. */
class CandidateConfigurationProof internal constructor(
    private val kind: String,
    private val identity: RuntimeConfigurationFacetIdentity,
) {
    internal fun matches(other: CandidateConfigurationProof): Boolean =
        kind == other.kind && identity.matches(other.identity)

    override fun toString(): String = "CandidateConfigurationProof([REDACTED])"
}

/** One process key for candidate inputs and later actual-consumed evidence. */
internal object CandidateConfigurationProofs {
    private val identities = RuntimeConfigurationIdentityFactory()

    fun relay(config: ResolvedRipDpiRelayConfig): CandidateConfigurationProof {
        val normalized =
            config.copy(
                localSocksHost = "",
                localSocksPort = 0,
                socketProtection = RelaySocketProtection.Inactive,
            )
        val material =
            listOf(
                RipDpiEncodeDefaultsJson.encodeToString(ResolvedRipDpiRelayConfig.serializer(), normalized),
                config.ptBridgeLine,
                config.ptWebTunnelUrl,
                config.ptSnowflakeBrokerUrl,
                config.ptSnowflakeFrontDomain,
            )
        return CandidateConfigurationProof("relay", identities.capture(material, emptyList()).transport)
    }

    fun xray(rendered: String): CandidateConfigurationProof {
        val config = RipDpiEncodeDefaultsJson.parseToJsonElement(rendered).jsonObject
        val inbounds = config["inbounds"] as? JsonArray ?: error("Xray inbound evidence is missing")
        val normalized =
            JsonArray(
                inbounds.map { value ->
                    val inbound = value.jsonObject
                    if (inbound["tag"] == JsonPrimitive("socks-in")) {
                        JsonObject(inbound + mapOf("listen" to JsonPrimitive(""), "port" to JsonPrimitive(0)))
                    } else {
                        inbound
                    }
                },
            )
        val canonical = JsonObject(config + ("inbounds" to normalized)).toString()
        return CandidateConfigurationProof("xray", identities.capture(listOf(canonical), emptyList()).transport)
    }
}
