package com.poyka.ripdpi.e2e

import android.content.Context
import android.os.Bundle
import com.poyka.ripdpi.BuildConfig
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val NetworkEvidenceActionReceiptFile = "network-evidence-action-receipt.json"
internal const val NetworkEvidenceStartupGateId = "killswitch-tun-establish-native-ready"
internal const val NetworkEvidenceStartupKind = "direct_window"
internal const val NetworkEvidenceStartupSelector =
    "com.poyka.ripdpi.e2e.VpnStartupWindowE2ETest#vpnStartupWindowHoldsDnsPacketUntilNativeReady"

private const val ReceiptVersion = "android_network_evidence_action_receipt_v1"
private const val WireMarkerPrefix = "RIPDPI-EVIDENCE-V2:"
private const val MarkerDomain = "ripdpi:network-evidence-marker:v2"
private const val CorrelationIdArg = "ripdpi.networkEvidenceCorrelationId"
private const val GateIdArg = "ripdpi.networkEvidenceGateId"
private const val KindArg = "ripdpi.networkEvidenceKind"
private const val SourceShaArg = "ripdpi.networkEvidenceSourceSha"
private const val ClientArtifactSha256Arg = "ripdpi.networkEvidenceClientArtifactSha256"
private const val TestArtifactSha256Arg = "ripdpi.networkEvidenceTestArtifactSha256"
private const val FixtureIdentitySha256Arg = "ripdpi.networkEvidenceFixtureIdentitySha256"
private const val ActionMarkerSha256Arg = "ripdpi.networkEvidenceActionMarkerSha256"
private const val OutcomeMarkerSha256Arg = "ripdpi.networkEvidenceOutcomeMarkerSha256"

private val Sha1Regex = Regex("[0-9a-f]{40}")
private val Sha256Regex = Regex("[0-9a-f]{64}")

internal data class NetworkEvidenceActionContext(
    val correlationId: String,
    val sourceSha: String,
    val clientArtifactSha256: String,
    val testArtifactSha256: String,
    val fixtureIdentitySha256: String,
    val actionMarkerSha256: String,
    val outcomeMarkerSha256: String,
) {
    val actionWireMarker: String
        get() = WireMarkerPrefix + actionMarkerSha256

    val outcomeWireMarker: String
        get() = WireMarkerPrefix + outcomeMarkerSha256
}

internal data class NetworkEvidenceStartupFacts(
    val startedAtElapsedRealtimeMs: Long,
    val finishedAtElapsedRealtimeMs: Long,
    val actionMarkerAtElapsedRealtimeMs: Long,
    val outcomeMarkerAtElapsedRealtimeMs: Long,
    val appUid: Int,
    val testUid: Int,
    val actionMarkerPid: Int,
    val actionMarkerUid: Int,
    val outcomeMarkerPid: Int,
    val outcomeMarkerUid: Int,
    val dnsProbePid: Int,
    val dnsProbeUid: Int,
    val tunFd: Int,
    val closedWindowRunningCount: Int,
    val preReadyDnsEventCount: Int,
    val startupWindowAssertionElapsedMs: Long,
    val dnsRcode: Int,
    val dnsAnswersExact: Boolean,
    val postReadyDnsEventCount: Int,
    val txPackets: Long,
    val rxPackets: Long,
)

internal fun networkEvidenceActionContextOrNull(arguments: Bundle): NetworkEvidenceActionContext? {
    val keys =
        listOf(
            CorrelationIdArg,
            GateIdArg,
            KindArg,
            SourceShaArg,
            ClientArtifactSha256Arg,
            TestArtifactSha256Arg,
            FixtureIdentitySha256Arg,
            ActionMarkerSha256Arg,
            OutcomeMarkerSha256Arg,
        )
    if (keys.none(arguments::containsKey)) {
        return null
    }

    fun required(
        key: String,
        pattern: Regex,
    ): String {
        val value = arguments.getString(key).orEmpty()
        require(pattern.matches(value)) { "Network evidence argument $key is missing or malformed" }
        return value
    }

    val correlationId = required(CorrelationIdArg, Sha256Regex)
    val gateId = required(GateIdArg, Regex(Regex.escape(NetworkEvidenceStartupGateId)))
    val kind = required(KindArg, Regex(Regex.escape(NetworkEvidenceStartupKind)))
    val sourceSha = required(SourceShaArg, Sha1Regex)
    require(
        BuildConfig.GIT_COMMIT.matches(Regex("[0-9a-f]{7,40}")) &&
            sourceSha.startsWith(BuildConfig.GIT_COMMIT),
    ) { "Installed app APK was not built from the attributed source SHA" }
    val clientArtifactSha256 = required(ClientArtifactSha256Arg, Sha256Regex)
    val testArtifactSha256 = required(TestArtifactSha256Arg, Sha256Regex)
    require(clientArtifactSha256 != testArtifactSha256) {
        "Client and androidTest artifacts must be distinct"
    }
    val fixtureIdentitySha256 = required(FixtureIdentitySha256Arg, Sha256Regex)
    val actionMarkerSha256 = required(ActionMarkerSha256Arg, Sha256Regex)
    val outcomeMarkerSha256 = required(OutcomeMarkerSha256Arg, Sha256Regex)
    require(actionMarkerSha256 != outcomeMarkerSha256) { "Network evidence markers must be distinct" }
    require(actionMarkerSha256 == deriveMarkerSha256(correlationId, gateId, kind, "action")) {
        "Network evidence action marker digest mismatch"
    }
    require(outcomeMarkerSha256 == deriveMarkerSha256(correlationId, gateId, kind, "outcome")) {
        "Network evidence outcome marker digest mismatch"
    }
    return NetworkEvidenceActionContext(
        correlationId = correlationId,
        sourceSha = sourceSha,
        clientArtifactSha256 = clientArtifactSha256,
        testArtifactSha256 = testArtifactSha256,
        fixtureIdentitySha256 = fixtureIdentitySha256,
        actionMarkerSha256 = actionMarkerSha256,
        outcomeMarkerSha256 = outcomeMarkerSha256,
    )
}

internal fun fixtureIdentitySha256(fixture: FixtureManifestDto): String {
    val fields =
        listOf(
            "bindHost" to fixture.bindHost,
            "androidHost" to fixture.androidHost,
            "tcpEchoPort" to fixture.tcpEchoPort.toString(),
            "udpEchoPort" to fixture.udpEchoPort.toString(),
            "tlsEchoPort" to fixture.tlsEchoPort.toString(),
            "dnsUdpPort" to fixture.dnsUdpPort.toString(),
            "dnsHttpPort" to fixture.dnsHttpPort.toString(),
            "dnsDotPort" to fixture.dnsDotPort.toString(),
            "dnsDnscryptPort" to fixture.dnsDnscryptPort.toString(),
            "dnsDoqPort" to fixture.dnsDoqPort.toString(),
            "socks5Port" to fixture.socks5Port.toString(),
            "controlPort" to fixture.controlPort.toString(),
            "fixtureDomain" to fixture.fixtureDomain,
            "fixtureIpv4" to fixture.fixtureIpv4,
            "dnsAnswerIpv4" to fixture.dnsAnswerIpv4,
            "tlsCertificatePem" to fixture.tlsCertificatePem,
            "dnscryptProviderName" to fixture.dnscryptProviderName,
            "dnscryptPublicKey" to fixture.dnscryptPublicKey,
        )
    val digest = MessageDigest.getInstance("SHA-256")
    updateLengthPrefixed(digest, "ripdpi:fixture-identity:v1")
    fields.forEach { (name, value) ->
        updateLengthPrefixed(digest, name)
        updateLengthPrefixed(digest, value)
    }
    return digest.digest().toHex()
}

internal fun clearNetworkEvidenceActionReceipt(context: Context) {
    val deletedOrAbsent =
        context.deleteFile(NetworkEvidenceActionReceiptFile) ||
            !context
                .getFileStreamPath(NetworkEvidenceActionReceiptFile)
                .exists()
    require(deletedOrAbsent) {
        "Could not clear stale network evidence action receipt"
    }
    File(context.filesDir, "$NetworkEvidenceActionReceiptFile.tmp").delete()
}

internal fun writeNetworkEvidenceStartupPassReceipt(
    context: Context,
    evidence: NetworkEvidenceActionContext,
    facts: NetworkEvidenceStartupFacts,
) {
    require(facts.startedAtElapsedRealtimeMs > 0)
    require(facts.startedAtElapsedRealtimeMs <= facts.actionMarkerAtElapsedRealtimeMs)
    require(facts.actionMarkerAtElapsedRealtimeMs < facts.outcomeMarkerAtElapsedRealtimeMs)
    require(facts.outcomeMarkerAtElapsedRealtimeMs <= facts.finishedAtElapsedRealtimeMs)
    require(facts.appUid > 0 && facts.testUid > 0 && facts.appUid != facts.testUid)
    require(facts.actionMarkerUid == facts.appUid && facts.outcomeMarkerUid == facts.appUid)
    require(facts.dnsProbeUid == facts.testUid)
    require(facts.actionMarkerPid > 0 && facts.outcomeMarkerPid > 0 && facts.dnsProbePid > 0)
    require(facts.tunFd >= 0)
    require(facts.closedWindowRunningCount == 0 && facts.preReadyDnsEventCount == 0)
    require(facts.startupWindowAssertionElapsedMs in 1 until 4_000)
    require(facts.dnsRcode == 0 && facts.dnsAnswersExact)
    require(facts.postReadyDnsEventCount == 1)
    require(facts.txPackets > 0 && facts.rxPackets > 0)

    val receipt =
        JSONObject()
            .put("version", ReceiptVersion)
            .put("status", "PASS")
            .put("gateId", NetworkEvidenceStartupGateId)
            .put("kind", NetworkEvidenceStartupKind)
            .put("selector", NetworkEvidenceStartupSelector)
            .put("correlationId", evidence.correlationId)
            .put("sourceSha", evidence.sourceSha)
            .put("clientArtifactSha256", evidence.clientArtifactSha256)
            .put("testArtifactSha256", evidence.testArtifactSha256)
            .put("fixtureIdentitySha256", evidence.fixtureIdentitySha256)
            .put("actionMarkerSha256", evidence.actionMarkerSha256)
            .put("outcomeMarkerSha256", evidence.outcomeMarkerSha256)
            .put("startedAtElapsedRealtimeMs", facts.startedAtElapsedRealtimeMs)
            .put("finishedAtElapsedRealtimeMs", facts.finishedAtElapsedRealtimeMs)
            .put("actionMarkerAtElapsedRealtimeMs", facts.actionMarkerAtElapsedRealtimeMs)
            .put("outcomeMarkerAtElapsedRealtimeMs", facts.outcomeMarkerAtElapsedRealtimeMs)
            .put("appAndTestUidsDistinct", true)
            .put("actionMarkerRanAsTargetApp", true)
            .put("outcomeMarkerRanAsTargetApp", true)
            .put("dnsProbeRanAsAndroidTest", true)
            .put("actionMarkerPidObserved", true)
            .put("outcomeMarkerPidObserved", true)
            .put("dnsProbePidObserved", true)
            .put("tunFdObserved", true)
            .put("closedWindowRunningCount", facts.closedWindowRunningCount)
            .put("preReadyDnsEventCount", facts.preReadyDnsEventCount)
            .put("startupWindowAssertionElapsedMs", facts.startupWindowAssertionElapsedMs)
            .put("dnsRcode", facts.dnsRcode)
            .put("dnsAnswersExact", facts.dnsAnswersExact)
            .put("postReadyDnsEventCount", facts.postReadyDnsEventCount)
            .put("txPackets", facts.txPackets)
            .put("rxPackets", facts.rxPackets)
            .put("finalStatus", "Halted")
            .put("gateClean", true)
    val temporary = File(context.filesDir, "$NetworkEvidenceActionReceiptFile.tmp")
    val destination = File(context.filesDir, NetworkEvidenceActionReceiptFile)
    check(!destination.exists()) { "Network evidence action receipt already exists" }
    context.openFileOutput(temporary.name, Context.MODE_PRIVATE).use { output ->
        output.write((receipt.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        output.fd.sync()
    }
    check(temporary.renameTo(destination)) { "Could not atomically publish network evidence action receipt" }
}

private fun deriveMarkerSha256(
    correlationId: String,
    gateId: String,
    kind: String,
    phase: String,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest("$MarkerDomain:$correlationId:$gateId:$kind:$phase".toByteArray(StandardCharsets.UTF_8))
        .toHex()

private fun updateLengthPrefixed(
    digest: MessageDigest,
    value: String,
) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    digest.update(bytes)
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
