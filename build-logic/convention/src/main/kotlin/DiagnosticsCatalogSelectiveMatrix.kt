import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.time.LocalDate

internal data class SelectiveMatrixDefinition(
    val version: Int = 1,
    val catalogVersion: String,
    val targets: List<SelectiveMatrixTargetDefinition>,
    val repetitions: Int = 2,
    val timeoutMs: Long = 5_000,
    val maxResponseBytes: Int = 65_536,
)

internal data class SelectiveMatrixTargetDefinition(
    val id: String,
    val label: String,
    val url: String,
    val cohort: String,
    val infrastructureGroup: String,
    val sourceUrl: String,
    val sourceDate: String,
    val lastVerifiedAt: String? = null,
    val legalSafetyMetadata: CatalogLegalSafetyMetadata? = null,
)

internal fun selectiveAvailabilityMatrixProfile(): DiagnosticsProfileDefinition =
    DiagnosticsProfileDefinition(
        id = "selective-availability-matrix",
        name = "Selective availability matrix",
        family = CatalogDiagnosticProfileFamily.WEB_CONNECTIVITY,
        regionTag = "ru",
        executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = false),
        selectiveMatrix =
            SelectiveMatrixDefinition(
                catalogVersion = "ru-selective-2026-10-09",
                targets =
                    listOf(
                        matrixTarget("yandex", "Yandex", "yandex.ru", "declared_available", "yandex"),
                        matrixTarget("vk", "VK", "vk.com", "declared_available", "vk"),
                        matrixTarget("selectel", "Selectel", "selectel.ru", "domestic", "selectel"),
                        matrixTarget("timeweb", "Timeweb", "timeweb.com", "domestic", "timeweb"),
                        matrixTarget("cloudflare", "Cloudflare", "speed.cloudflare.com", "global", "cloudflare"),
                        matrixTarget("ovh", "OVHcloud", "proof.ovh.net", "global", "ovhcloud"),
                    ),
            ),
    )

private fun matrixTarget(
    id: String,
    label: String,
    host: String,
    cohort: String,
    infrastructureGroup: String,
): SelectiveMatrixTargetDefinition {
    val declared = cohort == "declared_available"
    // The announcement covers services, not every hostname or future operator policy.
    // Other cohorts describe provider origin; they do not claim exclusion from an allowlist.
    // Their source date is the date of the endpoint observation, not its publication date.
    return SelectiveMatrixTargetDefinition(
        id = id,
        label = label,
        url = "https://$host/robots.txt",
        cohort = cohort,
        infrastructureGroup = infrastructureGroup,
        sourceUrl = if (declared) "https://t.me/mintsifry/2603" else "https://$host/robots.txt",
        sourceDate = if (declared) "2025-09-05" else "2026-10-09",
        // HTTPS checks returned complete 200 responses from this workstation on this date.
        // This timestamp is catalog maintenance evidence, not a current health guarantee.
        lastVerifiedAt = "2026-10-09T04:20:29Z",
    )
}

internal fun DiagnosticsProfileDefinition.validateSelectiveMatrix() {
    val matrix = selectiveMatrix ?: return
    require(executionPolicy.manualOnly && !executionPolicy.allowBackground) {
        "Matrix profile $id must be manual-only"
    }
    require(matrix.version == 1 && matrix.catalogVersion.isNotBlank()) { "Invalid matrix version in $id" }
    require(matrix.targets.size in 1..10 && matrix.repetitions in 1..3) { "Invalid matrix size in $id" }
    require(matrix.timeoutMs in 100..10_000 && matrix.maxResponseBytes in 1..65_536) { "Invalid matrix limits in $id" }
    require(matrix.targets.size.toLong() * matrix.repetitions * (matrix.maxResponseBytes + 16_384) <= 2_097_152) {
        "Matrix response budget exceeds 2 MiB in $id"
    }
    require(
        matrix.targets
            .map { it.id }
            .distinct()
            .size == matrix.targets.size,
    ) { "Duplicate matrix target in $id" }
    matrix.targets.forEach { target ->
        val uri = URI(target.url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Matrix target ${target.id} must use an HTTPS host"
        }
        require(uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null) {
            "Matrix target ${target.id} must not use a custom port, query or fragment"
        }
        require(target.cohort in setOf("declared_available", "domestic", "global", "user")) {
            "Invalid matrix cohort for ${target.id}"
        }
        require(target.id.isNotBlank() && target.label.isNotBlank() && target.infrastructureGroup.isNotBlank()) {
            "Matrix target metadata is missing for ${target.id}"
        }
        require(URI(target.sourceUrl).scheme == "https" && URI(target.sourceUrl).host != null) {
            "Matrix target provenance is missing for ${target.id}"
        }
        LocalDate.parse(target.sourceDate)
        target.lastVerifiedAt?.let(java.time.Instant::parse)
    }
}

internal fun SelectiveMatrixDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("version", version)
        put("catalogVersion", catalogVersion)
        put("targets", JsonArray(targets.map(SelectiveMatrixTargetDefinition::toJson)))
        put("repetitions", repetitions)
        put("timeoutMs", timeoutMs)
        put("maxResponseBytes", maxResponseBytes)
    }

private fun SelectiveMatrixTargetDefinition.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("label", label)
        put("url", url)
        put("cohort", cohort)
        put("infrastructureGroup", infrastructureGroup)
        put("sourceUrl", sourceUrl)
        put("sourceDate", sourceDate)
        lastVerifiedAt?.let { put("lastVerifiedAt", it) }
        legalSafetyMetadata?.let { metadata ->
            put(
                "legalSafety",
                buildJsonObject {
                    put("classification", metadata.classification.name)
                    put("shippingPolicy", metadata.shippingPolicy.name)
                    put("jurisdictionTag", metadata.jurisdictionTag)
                    put("ruleId", metadata.ruleId)
                },
            )
        }
    }
