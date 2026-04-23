import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

private val catalogJson =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        explicitNulls = false
    }

internal data class DiagnosticsCatalogLegalSafetyRule(
    val domain: String,
    val classification: CatalogLegalSafety,
    val shippingPolicy: CatalogLegalSafetyShippingPolicy,
    val jurisdictionTag: String,
    val ruleId: String,
)

internal class DiagnosticsCatalogLegalSafetyRegistry(
    rules: List<DiagnosticsCatalogLegalSafetyRule> = emptyList(),
) {
    private val rulesByDomain =
        rules.associateBy { it.domain.normalizeCatalogHost() }

    fun annotate(catalog: DiagnosticsCatalog): DiagnosticsCatalog =
        catalog.copy(
            packs = catalog.packs.map(::annotatePack),
            profiles = catalog.profiles.map(::annotateProfile),
        )

    fun denylistedDomains(): Set<String> =
        rulesByDomain.values
            .filter { it.shippingPolicy == CatalogLegalSafetyShippingPolicy.DENYLIST }
            .map { it.domain.normalizeCatalogHost() }
            .toSet()

    private fun annotatePack(pack: TargetPackDefinition): TargetPackDefinition =
        pack.copy(
            domainTargets = pack.domainTargets.map(::annotateDomainTarget),
            dnsTargets = pack.dnsTargets.map(::annotateDnsTarget),
            quicTargets = pack.quicTargets.map(::annotateQuicTarget),
            serviceTargets = pack.serviceTargets.map(::annotateServiceTarget),
            circumventionTargets = pack.circumventionTargets.map(::annotateCircumventionTarget),
            throughputTargets = pack.throughputTargets.map(::annotateThroughputTarget),
        )

    private fun annotateProfile(profile: DiagnosticsProfileDefinition): DiagnosticsProfileDefinition =
        profile.copy(
            domainTargets = profile.domainTargets.map(::annotateDomainTarget),
            dnsTargets = profile.dnsTargets.map(::annotateDnsTarget),
            quicTargets = profile.quicTargets.map(::annotateQuicTarget),
            serviceTargets = profile.serviceTargets.map(::annotateServiceTarget),
            circumventionTargets = profile.circumventionTargets.map(::annotateCircumventionTarget),
            throughputTargets = profile.throughputTargets.map(::annotateThroughputTarget),
        )

    private fun annotateDomainTarget(target: DomainTargetDefinition): DomainTargetDefinition {
        val metadata = metadataForHost(target.host)
        return target.copy(
            legalSafety = metadata?.classification ?: target.legalSafety,
            legalSafetyMetadata = metadata ?: target.legalSafetyMetadata ?: target.legalSafety.fallbackMetadata(),
        )
    }

    private fun annotateDnsTarget(target: DnsTargetDefinition): DnsTargetDefinition =
        target.copy(legalSafetyMetadata = metadataForHost(target.domain) ?: target.legalSafetyMetadata)

    private fun annotateQuicTarget(target: QuicTargetDefinition): QuicTargetDefinition =
        target.copy(legalSafetyMetadata = metadataForHost(target.host) ?: target.legalSafetyMetadata)

    private fun annotateServiceTarget(target: ServiceTargetDefinition): ServiceTargetDefinition =
        target.copy(
            legalSafetyMetadata =
                mergeMetadata(
                    target.legalSafetyMetadata,
                    metadataForHost(target.tcpEndpointHost),
                    metadataForUrl(target.bootstrapUrl),
                ),
        )

    private fun annotateCircumventionTarget(target: CircumventionTargetDefinition): CircumventionTargetDefinition =
        target.copy(
            legalSafetyMetadata =
                mergeMetadata(
                    target.legalSafetyMetadata,
                    metadataForHost(target.handshakeHost),
                    metadataForUrl(target.bootstrapUrl),
                ),
        )

    private fun annotateThroughputTarget(target: ThroughputTargetDefinition): ThroughputTargetDefinition =
        target.copy(legalSafetyMetadata = mergeMetadata(target.legalSafetyMetadata, metadataForUrl(target.url)))

    private fun metadataForHost(host: String?): CatalogLegalSafetyMetadata? =
        host
            ?.normalizeCatalogHost()
            ?.let(rulesByDomain::get)
            ?.toMetadata()

    private fun metadataForUrl(url: String?): CatalogLegalSafetyMetadata? =
        url
            ?.runCatching { URI(this).host }
            ?.getOrNull()
            ?.let(::metadataForHost)

    private fun DiagnosticsCatalogLegalSafetyRule.toMetadata(): CatalogLegalSafetyMetadata =
        CatalogLegalSafetyMetadata(
            classification = classification,
            shippingPolicy = shippingPolicy,
            jurisdictionTag = jurisdictionTag,
            ruleId = ruleId,
        )
}

internal class DiagnosticsCatalogValidator(
    private val legalSafetyRegistry: DiagnosticsCatalogLegalSafetyRegistry = DiagnosticsCatalogLegalSafetyRegistry(),
    private val repoRoot: Path? = null,
    private val generatedArtifactDirectories: List<String> = emptyList(),
    private val enforceGeneratedArtifactGuard: Boolean = false,
) {
    fun validate(catalog: DiagnosticsCatalog) {
        require(
            catalog.packs
                .map(TargetPackDefinition::id)
                .distinct()
                .size == catalog.packs.size,
        ) {
            "Diagnostics catalog contains duplicate pack ids"
        }
        require(
            catalog.profiles
                .map(DiagnosticsProfileDefinition::id)
                .distinct()
                .size == catalog.profiles.size,
        ) {
            "Diagnostics catalog contains duplicate profile ids"
        }

        val packsById = catalog.packs.associateBy(TargetPackDefinition::id)
        catalog.profiles.forEach { profile ->
            profile.packRefs.forEach { reference ->
                val (packId, version) = parsePackRef(reference)
                val pack =
                    requireNotNull(packsById[packId]) {
                        "Profile ${profile.id} references unknown pack $packId"
                    }
                require(pack.version == version) {
                    "Profile ${profile.id} references $reference but pack ${pack.id} is version ${pack.version}"
                }
            }

            val findings = resolveTargetFindings(profile, packsById)
            val highestTargetSafety =
                findings
                    .map(TargetSafetyFinding::classification)
                    .maxByOrNull(CatalogLegalSafety::severity)
                    ?: CatalogLegalSafety.SAFE
            when (profile.intentBucket) {
                CatalogProfileIntentBucket.SAFE_DEFAULT -> {
                    require(profile.legalSafety == CatalogLegalSafety.SAFE) {
                        "Safe-default profile ${profile.id} must be marked SAFE"
                    }
                    require(findings.none { it.classification == CatalogLegalSafety.UNSAFE }) {
                        "Safe-default profile ${profile.id} contains unsafe targets: ${findings.describeUnsafeTargets()}"
                    }
                    require(highestTargetSafety == CatalogLegalSafety.SAFE) {
                        "Safe-default profile ${profile.id} may only include SAFE targets"
                    }
                }

                CatalogProfileIntentBucket.MANUAL_SENSITIVE,
                CatalogProfileIntentBucket.LAB_ONLY,
                -> {
                    require(profile.legalSafety.severity >= highestTargetSafety.severity) {
                        "Profile ${profile.id} legalSafety ${profile.legalSafety} is weaker than target safety $highestTargetSafety"
                    }
                }
            }
        }
    }

    fun validateGeneratedArtifacts(renderedCatalog: String) {
        if (!enforceGeneratedArtifactGuard) {
            return
        }
        val denylistedDomains = legalSafetyRegistry.denylistedDomains()
        denylistedDomains.forEach { domain ->
            require(!renderedCatalog.contains(domain, ignoreCase = true)) {
                "Generated diagnostics catalog contains denylisted domain $domain"
            }
        }
        val root = repoRoot ?: return
        generatedArtifactDirectories
            .asSequence()
            .map(root::resolve)
            .filter(Files::exists)
            .flatMap(::walkGeneratedArtifacts)
            .forEach { artifact ->
                val contents = Files.readString(artifact)
                denylistedDomains.forEach { domain ->
                    require(!contents.contains(domain, ignoreCase = true)) {
                        val relativePath = root.relativize(artifact).toString()
                        "Generated diagnostics artifact $relativePath contains denylisted domain $domain"
                    }
                }
            }
    }

    private fun resolveTargetFindings(
        profile: DiagnosticsProfileDefinition,
        packsById: Map<String, TargetPackDefinition>,
    ): List<TargetSafetyFinding> =
        buildList {
            val referencedPacks =
                profile.packRefs.map { reference ->
                    val (packId, _) = parsePackRef(reference)
                    packsById.getValue(packId)
                }
            addAll(profile.domainTargets.mapNotNull { it.toFinding("domain target", it.host, it.effectiveMetadata()) })
            addAll(profile.dnsTargets.mapNotNull { it.toFinding("dns target", it.domain, it.legalSafetyMetadata) })
            addAll(profile.quicTargets.mapNotNull { it.toFinding("quic target", it.host, it.legalSafetyMetadata) })
            addAll(profile.serviceTargets.mapNotNull { it.toFinding("service target", it.id, it.legalSafetyMetadata) })
            addAll(
                profile.circumventionTargets.mapNotNull {
                    it.toFinding("circumvention target", it.id, it.legalSafetyMetadata)
                },
            )
            addAll(
                profile.throughputTargets.mapNotNull {
                    it.toFinding(
                        "throughput target",
                        it.id,
                        it.legalSafetyMetadata,
                    )
                },
            )
            referencedPacks.forEach { pack ->
                addAll(
                    pack.domainTargets.mapNotNull {
                        it.toFinding(
                            "pack domain target",
                            it.host,
                            it.effectiveMetadata(),
                        )
                    },
                )
                addAll(
                    pack.dnsTargets.mapNotNull { it.toFinding("pack dns target", it.domain, it.legalSafetyMetadata) },
                )
                addAll(
                    pack.quicTargets.mapNotNull { it.toFinding("pack quic target", it.host, it.legalSafetyMetadata) },
                )
                addAll(
                    pack.serviceTargets.mapNotNull {
                        it.toFinding(
                            "pack service target",
                            it.id,
                            it.legalSafetyMetadata,
                        )
                    },
                )
                addAll(
                    pack.circumventionTargets.mapNotNull {
                        it.toFinding("pack circumvention target", it.id, it.legalSafetyMetadata)
                    },
                )
                addAll(
                    pack.throughputTargets.mapNotNull {
                        it.toFinding("pack throughput target", it.id, it.legalSafetyMetadata)
                    },
                )
            }
        }
}

internal class DiagnosticsCatalogJsonRenderer {
    fun render(catalog: DiagnosticsCatalog): String =
        catalogJson.encodeToString(JsonElement.serializer(), catalog.toJson())
}

private data class TargetSafetyFinding(
    val label: String,
    val target: String,
    val classification: CatalogLegalSafety,
    val metadata: CatalogLegalSafetyMetadata,
)

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
        put("intentBucket", intentBucket.name)
        put("legalSafety", legalSafety.name)
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
        put("probePersistencePolicy", probePersistencePolicy.name)
    }

private fun DomainTargetDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("host", host)
        effectiveMetadata()?.let { put("legalSafety", it.toJson()) }
    }

private fun DnsTargetDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("domain", domain)
        udpServer?.let { put("udpServer", it) }
        legalSafetyMetadata?.let { put("legalSafety", it.toJson()) }
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
        legalSafetyMetadata?.let { put("legalSafety", it.toJson()) }
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
        legalSafetyMetadata?.let { put("legalSafety", it.toJson()) }
    }

private fun CircumventionTargetDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("tool", tool)
        bootstrapUrl?.let { put("bootstrapUrl", it) }
        handshakeHost?.let { put("handshakeHost", it) }
        legalSafetyMetadata?.let { put("legalSafety", it.toJson()) }
    }

private fun ThroughputTargetDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("label", label)
        put("url", url)
        put("isControl", isControl)
        put("windowBytes", windowBytes)
        put("runs", runs)
        legalSafetyMetadata?.let { put("legalSafety", it.toJson()) }
    }

private fun CatalogLegalSafetyMetadata.toJson(): JsonObject =
    buildJsonObject {
        put("classification", classification.name)
        put("shippingPolicy", shippingPolicy.name)
        put("jurisdictionTag", jurisdictionTag)
        put("ruleId", ruleId)
    }

private fun DomainTargetDefinition.effectiveMetadata(): CatalogLegalSafetyMetadata? =
    legalSafetyMetadata ?: legalSafety.fallbackMetadata()

private fun CatalogLegalSafety.fallbackMetadata(): CatalogLegalSafetyMetadata? =
    when (this) {
        CatalogLegalSafety.SAFE -> {
            null
        }

        CatalogLegalSafety.SENSITIVE -> {
            CatalogLegalSafetyMetadata(
                classification = CatalogLegalSafety.SENSITIVE,
                shippingPolicy = CatalogLegalSafetyShippingPolicy.MANUAL_ONLY,
                jurisdictionTag = "undifferentiated",
                ruleId = "inline_sensitive",
            )
        }

        CatalogLegalSafety.UNSAFE -> {
            CatalogLegalSafetyMetadata(
                classification = CatalogLegalSafety.UNSAFE,
                shippingPolicy = CatalogLegalSafetyShippingPolicy.DENYLIST,
                jurisdictionTag = "undifferentiated",
                ruleId = "inline_unsafe",
            )
        }
    }

private val CatalogLegalSafety.severity: Int
    get() =
        when (this) {
            CatalogLegalSafety.SAFE -> 0
            CatalogLegalSafety.SENSITIVE -> 1
            CatalogLegalSafety.UNSAFE -> 2
        }

private fun mergeMetadata(vararg metadata: CatalogLegalSafetyMetadata?): CatalogLegalSafetyMetadata? =
    metadata
        .filterNotNull()
        .maxWithOrNull(
            compareBy<CatalogLegalSafetyMetadata> { it.classification.severity }
                .thenBy { it.shippingPolicy.severity },
        )

private val CatalogLegalSafetyShippingPolicy.severity: Int
    get() =
        when (this) {
            CatalogLegalSafetyShippingPolicy.ALLOW -> 0
            CatalogLegalSafetyShippingPolicy.MANUAL_ONLY -> 1
            CatalogLegalSafetyShippingPolicy.DENYLIST -> 2
        }

private fun DomainTargetDefinition.toFinding(
    label: String,
    target: String,
    metadata: CatalogLegalSafetyMetadata?,
): TargetSafetyFinding? =
    metadata?.let {
        TargetSafetyFinding(
            label = label,
            target = target,
            classification = it.classification,
            metadata = it,
        )
    }

private fun DnsTargetDefinition.toFinding(
    label: String,
    target: String,
    metadata: CatalogLegalSafetyMetadata?,
): TargetSafetyFinding? =
    metadata?.let {
        TargetSafetyFinding(
            label = label,
            target = target,
            classification = it.classification,
            metadata = it,
        )
    }

private fun QuicTargetDefinition.toFinding(
    label: String,
    target: String,
    metadata: CatalogLegalSafetyMetadata?,
): TargetSafetyFinding? =
    metadata?.let {
        TargetSafetyFinding(
            label = label,
            target = target,
            classification = it.classification,
            metadata = it,
        )
    }

private fun ServiceTargetDefinition.toFinding(
    label: String,
    target: String,
    metadata: CatalogLegalSafetyMetadata?,
): TargetSafetyFinding? =
    metadata?.let {
        TargetSafetyFinding(
            label = label,
            target = target,
            classification = it.classification,
            metadata = it,
        )
    }

private fun CircumventionTargetDefinition.toFinding(
    label: String,
    target: String,
    metadata: CatalogLegalSafetyMetadata?,
): TargetSafetyFinding? =
    metadata?.let {
        TargetSafetyFinding(
            label = label,
            target = target,
            classification = it.classification,
            metadata = it,
        )
    }

private fun ThroughputTargetDefinition.toFinding(
    label: String,
    target: String,
    metadata: CatalogLegalSafetyMetadata?,
): TargetSafetyFinding? =
    metadata?.let {
        TargetSafetyFinding(
            label = label,
            target = target,
            classification = it.classification,
            metadata = it,
        )
    }

private fun List<TargetSafetyFinding>.describeUnsafeTargets(): String =
    filter { it.classification == CatalogLegalSafety.UNSAFE }
        .joinToString(separator = ", ") { finding ->
            "${finding.label} ${finding.target} (${finding.metadata.ruleId})"
        }

private fun walkGeneratedArtifacts(path: Path): Sequence<Path> =
    when {
        Files.isRegularFile(path) -> {
            sequenceOf(path)
        }

        Files.isDirectory(path) -> {
            Files.walk(path).use { stream ->
                stream
                    .filter(Files::isRegularFile)
                    .filter(::isGeneratedArtifactFile)
                    .toArray { size -> arrayOfNulls<Path>(size) }
                    .filterIsInstance<Path>()
                    .asSequence()
            }
        }

        else -> {
            emptySequence()
        }
    }

private fun isGeneratedArtifactFile(path: Path): Boolean =
    when (path.fileName.toString().substringAfterLast('.', "")) {
        "json", "txt", "kt" -> true
        else -> false
    }

private fun String.normalizeCatalogHost(): String =
    lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .trim()
