import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val catalogJson =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        explicitNulls = false
    }

internal class DiagnosticsCatalogValidator {
    fun validate(catalog: DiagnosticsCatalog) {
        require(catalog.packs.map(TargetPackDefinition::id).distinct().size == catalog.packs.size) {
            "Diagnostics catalog contains duplicate pack ids"
        }
        require(catalog.profiles.map(DiagnosticsProfileDefinition::id).distinct().size == catalog.profiles.size) {
            "Diagnostics catalog contains duplicate profile ids"
        }

        val packsById = catalog.packs.associateBy(TargetPackDefinition::id)
        catalog.profiles.forEach { profile ->
            profile.packRefs.forEach { reference ->
                val (packId, version) = parsePackRef(reference)
                val pack = requireNotNull(packsById[packId]) {
                    "Profile ${profile.id} references unknown pack $packId"
                }
                require(pack.version == version) {
                    "Profile ${profile.id} references $reference but pack ${pack.id} is version ${pack.version}"
                }
            }
        }
    }
}

internal class DiagnosticsCatalogJsonRenderer {
    fun render(catalog: DiagnosticsCatalog): String =
        catalogJson.encodeToString(JsonElement.serializer(), catalog.toJson())
}

private fun DiagnosticsCatalog.toJson(): JsonObject =
    buildJsonObject {
        put("schemaVersion", DiagnosticsCatalogSchemaVersion)
        put("generatedAt", DiagnosticsCatalogGeneratedAt)
        put("packs", JsonArray(packs.map(TargetPackDefinition::toCatalogJson)))
        put("profiles", JsonArray(profiles.map(DiagnosticsProfileDefinition::toCatalogJson)))
    }

private fun TargetPackDefinition.toCatalogJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("version", version)
    }

private fun DiagnosticsProfileDefinition.toCatalogJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("name", name)
        put("version", version)
        put("request", toRequestJson())
    }

private fun DiagnosticsProfileDefinition.toRequestJson(): JsonObject =
    buildJsonObject {
        put("profileId", id)
        put("displayName", name)
        put("kind", kind.name)
        put("family", family.name)
        regionTag?.let { put("regionTag", it) }
        put("executionPolicy", executionPolicy.toJson())
        put("packRefs", JsonArray(packRefs.map(::JsonPrimitive)))
        put("domainTargets", JsonArray(domainTargets.map(DomainTargetDefinition::toJson)))
        put("dnsTargets", JsonArray(dnsTargets.map(DnsTargetDefinition::toJson)))
        put("tcpTargets", JsonArray(tcpTargets.map(TcpTargetDefinition::toJson)))
        put("quicTargets", JsonArray(quicTargets.map(QuicTargetDefinition::toJson)))
        put("serviceTargets", JsonArray(serviceTargets.map(ServiceTargetDefinition::toJson)))
        put("circumventionTargets", JsonArray(circumventionTargets.map(CircumventionTargetDefinition::toJson)))
        put("throughputTargets", JsonArray(throughputTargets.map(ThroughputTargetDefinition::toJson)))
        put("whitelistSni", JsonArray(whitelistSni.map(::JsonPrimitive)))
        telegramTarget?.let { put("telegramTarget", it.toJson()) }
        strategyProbe?.let { put("strategyProbe", it.toJson()) }
    }

private fun ProfileExecutionPolicyDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("manualOnly", manualOnly)
        put("allowBackground", allowBackground)
        put("requiresRawPath", requiresRawPath)
    }

private fun DomainTargetDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("host", host)
    }

private fun DnsTargetDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("domain", domain)
        udpServer?.let { put("udpServer", it) }
    }

private fun TcpTargetDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("provider", provider)
        put("ip", ip)
        put("port", port)
        asn?.let { put("asn", it) }
    }

private fun QuicTargetDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("host", host)
        put("port", port)
    }

private fun TelegramTargetDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("mediaUrl", mediaUrl)
        put("uploadIp", uploadIp)
        put("uploadPort", uploadPort)
        put("dcEndpoints", JsonArray(dcEndpoints.map(TelegramDcEndpointDefinition::toJson)))
        put("stallTimeoutMs", stallTimeoutMs)
        put("totalTimeoutMs", totalTimeoutMs)
        put("uploadSizeBytes", uploadSizeBytes)
    }

private fun TelegramDcEndpointDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("ip", ip)
        put("label", label)
        put("port", port)
    }

private fun StrategyProbeDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("suiteId", suiteId)
    }

private fun ServiceTargetDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("service", service)
        bootstrapUrl?.let { put("bootstrapUrl", it) }
        tcpEndpointHost?.let { put("tcpEndpointHost", it) }
    }

private fun CircumventionTargetDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("tool", tool)
        bootstrapUrl?.let { put("bootstrapUrl", it) }
        handshakeHost?.let { put("handshakeHost", it) }
    }

private fun ThroughputTargetDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("label", label)
        put("url", url)
        put("isControl", isControl)
        put("windowBytes", windowBytes)
        put("runs", runs)
    }
