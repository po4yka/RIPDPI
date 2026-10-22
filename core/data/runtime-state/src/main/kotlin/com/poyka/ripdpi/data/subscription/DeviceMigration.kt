package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.serialization.RipDpiEncodeDefaultsJson
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Secret-bearing delivery authority; sealed with its owning subscription and never exported directly. */
@Serializable
data class MigrationMetadata(
    val migrationId: String,
    val deliveryId: String,
    val targetTags: List<String>,
    val receiptKey: String,
    val awgPreviousTags: Map<String, String>,
) {
    override fun toString(): String = "MigrationMetadata([REDACTED])"
}

@Serializable
data class MigrationTarget(
    val tag: String,
    val profileId: String,
    val amneziaWg: Boolean,
    val fingerprint: String,
)

@Serializable
data class MigrationPathTest(
    val tag: String,
    val testedAtMillis: Long,
    val fingerprint: String,
)

@Serializable
data class MigrationDelivery(
    val metadata: MigrationMetadata,
    val payloadSha256: String,
    val receivedAtMillis: Long,
    val targets: List<MigrationTarget>,
    val pathTest: MigrationPathTest? = null,
) {
    override fun toString(): String = "MigrationDelivery([REDACTED])"
}

fun migrationSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun ProxyProfile.migrationFingerprint(): String =
    migrationSha256(
        RipDpiEncodeDefaultsJson.encodeToString(ProxyProfile.serializer(), this).toByteArray(Charsets.UTF_8),
    )

/** Only private-key completion is local: every delivered field remains part of target identity. */
fun AwgActivationRequest.migrationFingerprint(includePrivateKey: Boolean = false): String =
    migrationSha256(
        RipDpiEncodeDefaultsJson
            .encodeToString(
                AwgActivationRequest.serializer(),
                if (includePrivateKey) this else copy(privateKey = ""),
            ).toByteArray(Charsets.UTF_8),
    )

fun receivedMigration(
    metadata: MigrationMetadata?,
    payloadSha256: String,
    receivedAtMillis: Long,
    profiles: List<ProxyProfile>,
    awgTargets: List<MigrationTarget>,
    previous: MigrationDelivery? = null,
): MigrationDelivery? {
    if (metadata == null) return null
    metadata.requireValid()
    require(receivedAtMillis > 0) { "invalid migration clock" }
    val candidates =
        profiles.map { MigrationTarget(it.displayName, it.id, false, it.migrationFingerprint()) } + awgTargets
    val targets = metadata.targetTags.map { tag -> candidates.single { it.tag == tag } }
    return if (previous?.metadata == metadata && previous.payloadSha256 == payloadSha256 &&
        previous.targets == targets
    ) {
        previous
    } else {
        MigrationDelivery(metadata, payloadSha256, receivedAtMillis, targets)
    }
}
