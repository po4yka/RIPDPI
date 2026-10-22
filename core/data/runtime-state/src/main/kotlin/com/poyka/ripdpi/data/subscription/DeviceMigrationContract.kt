package com.poyka.ripdpi.data.subscription

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val MigrationUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val MigrationHex = Regex("[0-9a-f]{64}")
private val MigrationTag = Regex("[A-Za-z0-9_.:-]{1,128}")
private const val MaximumMigrationTargets = 128
private const val MigrationHexRadix = 16
private val MigrationKeys =
    setOf("schema_version", "migration_id", "delivery_id", "target_tags", "receipt_key", "awg_previous_tags")

internal fun parseMigrationMetadata(
    root: JsonElement,
    importedTags: List<String>,
    awgTags: List<String>,
): MigrationMetadata? {
    val extension = (root as? JsonObject)?.get("ripdpi") as? JsonObject
    val element = extension?.get("migration") ?: return null
    require(extension["schema_version"] == JsonPrimitive(1)) { "unsupported migration extension" }
    val obj = element as? JsonObject ?: error("invalid migration metadata")
    require(obj.keys == MigrationKeys && obj["schema_version"] == JsonPrimitive(1)) { "invalid migration metadata" }
    val metadata =
        MigrationMetadata(
            migrationId = obj.requiredMigrationString("migration_id"),
            deliveryId = obj.requiredMigrationString("delivery_id"),
            targetTags =
                (obj["target_tags"] as? JsonArray ?: error("invalid migration targets")).map {
                    require(it is JsonPrimitive && it.isString) { "invalid migration target" }
                    it.content
                },
            receiptKey = obj.requiredMigrationString("receipt_key"),
            awgPreviousTags =
                (obj["awg_previous_tags"] as? JsonObject ?: error("invalid AWG origins")).mapValues { (_, value) ->
                    require(value is JsonPrimitive && value.isString) { "invalid AWG origin" }
                    value.content
                },
        )
    metadata.requireValid()
    require(metadata.targetTags.all { tag -> importedTags.count { it == tag } == 1 }) { "ambiguous migration target" }
    require(metadata.awgPreviousTags.keys.all { it in awgTags }) { "invalid AWG target" }
    return metadata
}

private fun JsonObject.requiredMigrationString(key: String): String {
    val value = this[key] as? JsonPrimitive
    require(value != null && value.isString) { "invalid migration field" }
    return value.content
}

internal fun MigrationMetadata.requireValid() {
    require(MigrationUuid.matches(migrationId) && MigrationUuid.matches(deliveryId)) { "invalid migration identity" }
    require(MigrationHex.matches(receiptKey)) { "invalid migration key" }
    require(targetTags.size in 1..MaximumMigrationTargets && targetTags.distinct().size == targetTags.size) {
        "invalid migration targets"
    }
    require(targetTags.all(MigrationTag::matches)) { "invalid migration target" }
    require(
        awgPreviousTags.size <= MaximumMigrationTargets &&
            awgPreviousTags.keys.all {
                it in targetTags
            },
    ) { "invalid AWG origins" }
    require(
        awgPreviousTags.values.all(MigrationTag::matches) &&
            awgPreviousTags.values.distinct().size == awgPreviousTags.size,
    ) { "ambiguous AWG origin" }
}

/** Exact fixed-field wire format. No secret-bearing object is passed to a general serializer. */
fun MigrationDelivery.exportReceipt(): String {
    metadata.requireValid()
    require(MigrationHex.matches(payloadSha256) && receivedAtMillis > 0) { "invalid migration receipt" }
    val tested = pathTest
    require(tested == null || (tested.tag in metadata.targetTags && tested.testedAtMillis >= receivedAtMillis)) {
        "invalid migration test"
    }
    val state = if (tested == null) "received" else "path-tested"
    val testedAt = tested?.testedAtMillis ?: 0L
    val testedTag = tested?.tag.orEmpty()
    val message =
        listOf(
            "ripdpi-device-migration-receipt-v1",
            metadata.migrationId,
            metadata.deliveryId,
            payloadSha256,
            state,
            receivedAtMillis.toString(),
            testedAt.toString(),
            testedTag,
            "",
        ).joinToString("\n")
    val key =
        metadata.receiptKey
            .chunked(2)
            .map { it.toInt(MigrationHexRadix).toByte() }
            .toByteArray()
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    val proof = mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    return buildJsonObject {
        put("schema_version", 1)
        put("migration_id", metadata.migrationId)
        put("delivery_id", metadata.deliveryId)
        put("payload_sha256", payloadSha256)
        put("state", state)
        put("received_at_ms", receivedAtMillis)
        put("tested_at_ms", testedAt)
        put("tested_tag", testedTag)
        put("mac", proof)
    }.toString()
}
