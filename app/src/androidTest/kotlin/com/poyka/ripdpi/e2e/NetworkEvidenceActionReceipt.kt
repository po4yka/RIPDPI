package com.poyka.ripdpi.e2e

import android.content.Context
import android.net.InetAddresses
import android.os.Bundle
import android.system.Os
import com.poyka.ripdpi.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val NetworkEvidenceStartupGateId = "killswitch-tun-establish-native-ready"
internal const val NetworkEvidenceStartupKind = "direct_window"
internal const val NetworkEvidenceStartupSelector =
    "com.poyka.ripdpi.e2e.VpnStartupWindowE2ETest#vpnStartupWindowHoldsDnsPacketUntilNativeReady"

internal const val NetworkEvidenceActionReceiptFile =
    "network-evidence-action-receipt-killswitch-tun-establish-native-ready.json"
private const val NetworkEvidenceStartupRule = "tun-establish-native-ready-v1"
private const val ReceiptVersion = "android_network_evidence_action_receipt_v3"
private const val WireMarkerPrefix = "RIPDPI-EVIDENCE-V2:"
private const val MarkerDomain = "ripdpi:network-evidence-marker:v2"
private const val CorrelationIdArg = "ripdpi.networkEvidenceCorrelationId"
private const val GateIdArg = "ripdpi.networkEvidenceGateId"
private const val KindArg = "ripdpi.networkEvidenceKind"
private const val SelectorArg = "ripdpi.networkEvidenceSelector"
private const val SemanticRuleArg = "ripdpi.networkEvidenceSemanticRule"
private const val ReceiptFileArg = "ripdpi.networkEvidenceReceiptFile"
private const val SourceShaArg = "ripdpi.networkEvidenceSourceSha"
private const val ClientArtifactSha256Arg = "ripdpi.networkEvidenceClientArtifactSha256"
private const val TestArtifactSha256Arg = "ripdpi.networkEvidenceTestArtifactSha256"
private const val FixtureIdentitySha256Arg = "ripdpi.networkEvidenceFixtureIdentitySha256"
private const val ActionMarkerSha256Arg = "ripdpi.networkEvidenceActionMarkerSha256"
private const val OutcomeMarkerSha256Arg = "ripdpi.networkEvidenceOutcomeMarkerSha256"

private val Sha1Regex = Regex("[0-9a-f]{40}")
private val Sha256Regex = Regex("[0-9a-f]{64}")

private data class EvidenceDescriptor(
    val kind: String,
    val selector: String,
    val semanticRule: String,
)

private val EvidenceDescriptors =
    mapOf(
        NetworkEvidenceStartupGateId to
            EvidenceDescriptor(NetworkEvidenceStartupKind, NetworkEvidenceStartupSelector, NetworkEvidenceStartupRule),
        "dns-virtual-vpn-resolver" to
            EvidenceDescriptor(
                "dns",
                "com.poyka.ripdpi.e2e.DnsNetworkEvidenceE2ETest#virtualVpnResolverUsesTunnelledResolver",
                "virtual-vpn-resolver-v1",
            ),
        "dns-proxied-through-tunnelled-resolver" to
            EvidenceDescriptor(
                "dns",
                "com.poyka.ripdpi.e2e.DnsNetworkEvidenceE2ETest#proxiedDomainUsesTunnelledResolver",
                "proxied-domain-resolver-path-v1",
            ),
        "dns-no-isp-fallback-on-encrypted-resolver-outage" to
            EvidenceDescriptor(
                "dns",
                "com.poyka.ripdpi.e2e.DnsNetworkEvidenceE2ETest#encryptedResolverOutageFailsClosed",
                "encrypted-outage-fail-closed-v1",
            ),
        "dns-allowlisted-bootstrap-resolution" to
            EvidenceDescriptor(
                "dns",
                "com.poyka.ripdpi.e2e.DnsNetworkEvidenceE2ETest#bootstrapResolutionUsesAllowlistedResolver",
                "allowlisted-bootstrap-v1",
            ),
        "dns-network-switch-behavior" to
            EvidenceDescriptor(
                "dns",
                "com.poyka.ripdpi.e2e.DnsNetworkEvidenceE2ETest#networkSwitchPreservesDnsTunnel",
                "network-switch-dns-continuity-v1",
            ),
        "dns-core-crash-behavior" to
            EvidenceDescriptor(
                "dns",
                "com.poyka.ripdpi.e2e.DnsNetworkEvidenceE2ETest#coreCrashFailsDnsClosed",
                "core-crash-dns-fail-closed-v1",
            ),
        "dns-android-private-dns-conflict" to
            EvidenceDescriptor(
                "dns",
                "com.poyka.ripdpi.e2e.DnsNetworkEvidenceE2ETest#privateDnsConflictFailsClosed",
                "android-private-dns-conflict-v1",
            ),
    )

internal data class NetworkEvidenceActionContext(
    val gateId: String,
    val kind: String,
    val selector: String,
    val semanticRule: String,
    val receiptFile: String,
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
    val dnsQuerySha256: String,
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
            SelectorArg,
            SemanticRuleArg,
            ReceiptFileArg,
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
    val gateId = required(GateIdArg, Regex("[a-z0-9][a-z0-9_-]{0,127}"))
    val descriptor = requireNotNull(EvidenceDescriptors[gateId]) { "Unknown network evidence gate" }
    val kind = required(KindArg, Regex(Regex.escape(descriptor.kind)))
    val selector = required(SelectorArg, Regex(Regex.escape(descriptor.selector)))
    val semanticRule = required(SemanticRuleArg, Regex(Regex.escape(descriptor.semanticRule)))
    val receiptFile = required(ReceiptFileArg, Regex(Regex.escape("network-evidence-action-receipt-$gateId.json")))
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
        gateId = gateId,
        kind = kind,
        selector = selector,
        semanticRule = semanticRule,
        receiptFile = receiptFile,
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

internal fun clearNetworkEvidenceActionReceipt(
    context: Context,
    receiptFile: String = NetworkEvidenceActionReceiptFile,
) {
    val deletedOrAbsent =
        context.deleteFile(receiptFile) ||
            !context
                .getFileStreamPath(receiptFile)
                .exists()
    require(deletedOrAbsent) {
        "Could not clear stale network evidence action receipt"
    }
    File(context.filesDir, "$receiptFile.tmp").delete()
}

internal fun clearNetworkEvidenceFixtureTranscript(
    context: Context,
    gateId: String,
) {
    val name = "network-evidence-fixture-transcript-$gateId.json"
    require(context.deleteFile(name) || !context.getFileStreamPath(name).exists()) {
        "Could not clear stale network evidence fixture transcript"
    }
    File(context.filesDir, "$name.tmp").delete()
}

internal data class NetworkEvidenceDnsFacts(
    val gateId: String,
    val queryHost: String,
    val resolverEndpoint: String,
    val transcriptSha256: String,
    val startedAtElapsedRealtimeMs: Long,
    val actionMarkerAtElapsedRealtimeMs: Long,
    val outcomeMarkerAtElapsedRealtimeMs: Long,
    val finishedAtElapsedRealtimeMs: Long,
    val appUid: Int,
    val testUid: Int,
    val actionMarker: AppProcessTcpProbeResult,
    val outcomeMarker: AppProcessTcpProbeResult,
    val dnsProbe: AppProcessDnsProbeResult,
    val tunnelTelemetry: com.poyka.ripdpi.data.NativeRuntimeSnapshot,
    val faultObserved: Boolean,
    val socksTarget: String?,
    /** Host and port of the fixture's plaintext UDP resolver, for packet-verified gates. */
    val udpResolverHost: String,
    val udpResolverPort: Int,
) {
    companion object {
        fun fromObserved(
            gateId: String,
            queryHost: String,
            fixture: FixtureManifestDto,
            transcriptSha256: String,
            startedAtElapsedRealtimeMs: Long,
            actionMarkerAtElapsedRealtimeMs: Long,
            outcomeMarkerAtElapsedRealtimeMs: Long,
            finishedAtElapsedRealtimeMs: Long,
            appUid: Int,
            testUid: Int,
            actionMarker: AppProcessTcpProbeResult,
            outcomeMarker: AppProcessTcpProbeResult,
            dnsProbe: AppProcessDnsProbeResult,
            tunnelTelemetry: com.poyka.ripdpi.data.NativeRuntimeSnapshot,
            faultObserved: Boolean,
            socksTarget: String?,
        ): NetworkEvidenceDnsFacts {
            require(fixture.androidHost.isNotBlank() && fixture.dnsDotPort in 1..65535)
            require(fixture.dnsUdpPort in 1..65535)
            return NetworkEvidenceDnsFacts(
                gateId,
                queryHost,
                "${fixture.androidHost}:${fixture.dnsDotPort}",
                transcriptSha256,
                startedAtElapsedRealtimeMs,
                actionMarkerAtElapsedRealtimeMs,
                outcomeMarkerAtElapsedRealtimeMs,
                finishedAtElapsedRealtimeMs,
                appUid,
                testUid,
                actionMarker,
                outcomeMarker,
                dnsProbe,
                tunnelTelemetry,
                faultObserved,
                socksTarget,
                fixture.androidHost,
                fixture.dnsUdpPort,
            )
        }
    }
}

internal data class NetworkEvidenceDnsScenarioObservation(
    val startedAtElapsedRealtimeMs: Long,
    val actionMarkerAtElapsedRealtimeMs: Long,
    val outcomeMarkerAtElapsedRealtimeMs: Long,
    val finishedAtElapsedRealtimeMs: Long,
    val appUid: Int,
    val testUid: Int,
    val actionMarker: AppProcessTcpProbeResult,
    val outcomeMarker: AppProcessTcpProbeResult,
    val dnsProbes: List<AppProcessDnsProbeResult>,
)

internal fun writeNetworkEvidenceFixtureTranscript(
    context: Context,
    evidence: NetworkEvidenceActionContext,
    queryHost: String,
    events: List<FixtureEventDto>,
): String {
    val queryEvent = events.singleOrNull { it.service == "dns_dot" && it.detail == queryHost }
    val queryPeer = queryEvent?.peer
    val scenarioSocksEvent =
        queryEvent?.let { query ->
            events
                .filter {
                    it.service == "socks5_relay" &&
                        it.protocol == "tcp" &&
                        it.detail == query.target &&
                        it.createdAt <= query.createdAt
                }.maxByOrNull { it.createdAt }
        }
    val relevant =
        events.filter { event ->
            (
                event.service == "dns_dot" &&
                    (event.detail == queryHost || (event.peer == queryPeer && event.detail.startsWith("fault:")))
            ) ||
                (
                    evidence.gateId == "dns-allowlisted-bootstrap-resolution" &&
                        event.service == "dns_udp" &&
                        event.detail == queryHost
                ) ||
                event == scenarioSocksEvent
        }
    require(relevant.isNotEmpty()) { "Fixture transcript is empty" }
    val inventoryEvents =
        events.filter { event ->
            event in relevant ||
                (event.service == "dns_dot" && event.peer == queryPeer && event.detail == "accept")
        }
    val document =
        JSONObject()
            .put("version", "network_evidence_fixture_transcript_v2")
            .put("gateId", evidence.gateId)
            .put("correlationId", evidence.correlationId)
            .put("fixtureIdentitySha256", evidence.fixtureIdentitySha256)
            .put("queryHost", queryHost.lowercase())
            .put("querySha256", networkEvidenceDnsQuerySha256V1(queryHost))
            .put(
                "eventInventory",
                JSONArray().apply {
                    inventoryEvents.forEach { event ->
                        put(redactedFixtureEventInventoryEntry(event))
                    }
                },
            ).put(
                "events",
                JSONArray().apply {
                    relevant.forEach { event ->
                        put(
                            JSONObject()
                                .put("service", event.service)
                                .put("protocol", event.protocol)
                                .put("peer", event.peer)
                                .put("target", event.target)
                                .put("detail", event.detail)
                                .put("bytes", event.bytes)
                                .put("sni", event.sni ?: JSONObject.NULL)
                                .put("createdAt", event.createdAt),
                        )
                    }
                },
            )
    val bytes = (networkEvidenceCanonicalJson(document) + "\n").toByteArray(StandardCharsets.UTF_8)
    val name = "network-evidence-fixture-transcript-${evidence.gateId}.json"
    val temporary = File(context.filesDir, "$name.tmp")
    val destination = File(context.filesDir, name)
    check(!destination.exists()) { "Network evidence fixture transcript already exists" }
    context.openFileOutput(temporary.name, Context.MODE_PRIVATE).use { output ->
        output.write(bytes)
        output.fd.sync()
    }
    enforceOwnerOnlyMode(temporary)
    check(temporary.renameTo(destination)) { "Could not atomically publish fixture transcript" }
    return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}

private fun redactedFixtureEventInventoryEntry(event: FixtureEventDto): JSONObject {
    val knownServices = setOf("dns_dot", "dns_udp", "dns_http", "dns_dnscrypt", "dns_doq", "socks5_relay")
    val knownProtocols = setOf("dot", "udp", "http", "dnscrypt", "doq", "tcp")
    return JSONObject()
        .put("service", event.service.takeIf(knownServices::contains) ?: "other")
        .put("protocol", event.protocol.takeIf(knownProtocols::contains) ?: "other")
        .put("peerSha256", fixtureEventFieldSha256("peer", event.peer))
        .put("targetSha256", fixtureEventFieldSha256("target", event.target))
        .put("detailSha256", fixtureEventFieldSha256("detail", event.detail))
        .put("bytes", event.bytes)
        .put("sniSha256", event.sni?.let { fixtureEventFieldSha256("sni", it) } ?: JSONObject.NULL)
        .put("createdAt", event.createdAt)
}

private fun fixtureEventFieldSha256(
    field: String,
    value: String,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest("ripdpi:network-evidence-fixture-event-field:v1:$field:$value".toByteArray(StandardCharsets.UTF_8))
        .toHex()

internal fun writeNetworkEvidenceDnsPassReceipt(
    context: Context,
    evidence: NetworkEvidenceActionContext,
    facts: NetworkEvidenceDnsFacts,
) {
    require(evidence.gateId == facts.gateId)
    require(facts.startedAtElapsedRealtimeMs > 0)
    require(facts.startedAtElapsedRealtimeMs <= facts.actionMarkerAtElapsedRealtimeMs)
    require(facts.actionMarkerAtElapsedRealtimeMs < facts.outcomeMarkerAtElapsedRealtimeMs)
    require(facts.outcomeMarkerAtElapsedRealtimeMs <= facts.finishedAtElapsedRealtimeMs)
    require(facts.appUid > 0 && facts.testUid > 0 && facts.appUid != facts.testUid)
    require(facts.actionMarker.probeUid == facts.appUid && facts.outcomeMarker.probeUid == facts.appUid)
    require(facts.dnsProbe.probeUid == facts.testUid)
    require(Sha256Regex.matches(facts.transcriptSha256))
    val querySha = networkEvidenceDnsQuerySha256V1(facts.queryHost)
    val providerSha = networkEvidenceEndpointSha256("dot", facts.resolverEndpoint)
    val semanticFacts =
        when (facts.gateId) {
            "dns-virtual-vpn-resolver" -> {
                JSONObject()
                    .put("virtualQuerySha256", querySha)
                    .put(
                        "virtualDnsAddressSha256",
                        networkEvidenceValueSha256("virtual-dns", VirtualDnsAddressForEvidence),
                    ).put("tunnelResolverProviderSha256", providerSha)
                    .put("fixtureTranscriptSha256", facts.transcriptSha256)
                    .put("resolverPath", "tunnel")
                    .put("responseCount", 1)
                    .put("tunnelEstablished", true)
            }

            "dns-proxied-through-tunnelled-resolver" -> {
                JSONObject()
                    .put("proxiedQuerySha256", querySha)
                    .put("resolverProviderSha256", providerSha)
                    .put("fixtureTranscriptSha256", facts.transcriptSha256)
                    .put(
                        "socksTargetSha256",
                        networkEvidenceValueSha256("socks-target", requireNotNull(facts.socksTarget)),
                    ).put("routeClass", "proxy")
                    .put("relayDnsRoute", requireNotNull(facts.tunnelTelemetry.relayDnsRoute))
                    .put("relayDnsFailClosed", facts.tunnelTelemetry.relayDnsFailClosed)
                    .put("responseCount", 1)
                    .put("tunnelEstablished", true)
            }

            "dns-no-isp-fallback-on-encrypted-resolver-outage" -> {
                JSONObject()
                    .put("outageQuerySha256", querySha)
                    .put("encryptedResolverProviderSha256", providerSha)
                    .put("fixtureTranscriptSha256", facts.transcriptSha256)
                    .put("faultControlSha256", networkEvidenceValueSha256("fault", "dns_dot:dns_timeout:persistent"))
                    .put("faultObserved", facts.faultObserved)
                    .put("encryptedAttemptCount", 1)
                    .put("plainFallbackCount", 0)
                    .put("successfulResponseCount", 0)
            }

            "dns-allowlisted-bootstrap-resolution" -> {
                // Packet-verified gate: every resolver digest here must match
                // what the capture observed, so the byte-form helper is used.
                val observedResolverSha =
                    networkEvidencePacketResolverSha256(facts.udpResolverHost, facts.udpResolverPort)
                JSONObject()
                    .put("bootstrapQuerySha256", querySha)
                    .put("allowlistedResolverSetSha256", networkEvidenceResolverSetSha256(observedResolverSha))
                    .put("observedBootstrapResolverSha256", observedResolverSha)
                    .put("bootstrapQueryCount", 1)
                    .put("nonAllowlistedBootstrapCount", 0)
                    .put("observedResolverAllowlisted", true)
            }

            else -> {
                error("Unsupported DNS evidence gate ${facts.gateId}")
            }
        }
    publishNetworkEvidenceDnsPassReceipt(
        context = context,
        evidence = evidence,
        observation =
            NetworkEvidenceDnsScenarioObservation(
                startedAtElapsedRealtimeMs = facts.startedAtElapsedRealtimeMs,
                actionMarkerAtElapsedRealtimeMs = facts.actionMarkerAtElapsedRealtimeMs,
                outcomeMarkerAtElapsedRealtimeMs = facts.outcomeMarkerAtElapsedRealtimeMs,
                finishedAtElapsedRealtimeMs = facts.finishedAtElapsedRealtimeMs,
                appUid = facts.appUid,
                testUid = facts.testUid,
                actionMarker = facts.actionMarker,
                outcomeMarker = facts.outcomeMarker,
                dnsProbes = listOf(facts.dnsProbe),
            ),
        semanticFacts = semanticFacts,
    )
}

internal fun writeNetworkEvidenceDnsScenarioPassReceipt(
    context: Context,
    evidence: NetworkEvidenceActionContext,
    observation: NetworkEvidenceDnsScenarioObservation,
    semanticFacts: JSONObject,
) {
    publishNetworkEvidenceDnsPassReceipt(context, evidence, observation, semanticFacts)
}

private fun publishNetworkEvidenceDnsPassReceipt(
    context: Context,
    evidence: NetworkEvidenceActionContext,
    observation: NetworkEvidenceDnsScenarioObservation,
    semanticFacts: JSONObject,
) {
    require(observation.startedAtElapsedRealtimeMs > 0)
    require(observation.startedAtElapsedRealtimeMs <= observation.actionMarkerAtElapsedRealtimeMs)
    require(observation.actionMarkerAtElapsedRealtimeMs < observation.outcomeMarkerAtElapsedRealtimeMs)
    require(observation.outcomeMarkerAtElapsedRealtimeMs <= observation.finishedAtElapsedRealtimeMs)
    require(observation.appUid > 0 && observation.testUid > 0 && observation.appUid != observation.testUid)
    require(observation.actionMarker.probeUid == observation.appUid)
    require(observation.outcomeMarker.probeUid == observation.appUid)
    require(observation.dnsProbes.isNotEmpty())
    require(observation.dnsProbes.all { it.probeUid == observation.testUid })
    val queryDigests =
        semanticFacts
            .keys()
            .asSequence()
            .filter { it.endsWith("QuerySha256", ignoreCase = true) }
            .associateWith { semanticFacts.getString(it) }
    require(queryDigests.isNotEmpty() && queryDigests.values.all(Sha256Regex::matches))
    require(queryDigests.values.toSet().size == queryDigests.size)
    val receipt =
        JSONObject()
            .put("version", ReceiptVersion)
            .put("status", "PASS")
            .put("gateId", evidence.gateId)
            .put("kind", evidence.kind)
            .put("selector", evidence.selector)
            .put("semanticRule", evidence.semanticRule)
            .put("correlationId", evidence.correlationId)
            .put("sourceSha", evidence.sourceSha)
            .put("clientArtifactSha256", evidence.clientArtifactSha256)
            .put("testArtifactSha256", evidence.testArtifactSha256)
            .put("fixtureIdentitySha256", evidence.fixtureIdentitySha256)
            .put("actionMarkerSha256", evidence.actionMarkerSha256)
            .put("outcomeMarkerSha256", evidence.outcomeMarkerSha256)
            .put("querySetSha256", networkEvidenceQuerySetSha256(evidence.gateId, queryDigests))
            .put("startedAtElapsedRealtimeMs", observation.startedAtElapsedRealtimeMs)
            .put("finishedAtElapsedRealtimeMs", observation.finishedAtElapsedRealtimeMs)
            .put("actionMarkerAtElapsedRealtimeMs", observation.actionMarkerAtElapsedRealtimeMs)
            .put("outcomeMarkerAtElapsedRealtimeMs", observation.outcomeMarkerAtElapsedRealtimeMs)
            .put("appUid", observation.appUid)
            .put("testUid", observation.testUid)
            .put("actionMarkerPid", requireNotNull(observation.actionMarker.probePid))
            .put("actionMarkerUid", requireNotNull(observation.actionMarker.probeUid))
            .put("outcomeMarkerPid", requireNotNull(observation.outcomeMarker.probePid))
            .put("outcomeMarkerUid", requireNotNull(observation.outcomeMarker.probeUid))
            .put(
                "dnsProbePids",
                JSONArray().apply {
                    observation.dnsProbes
                        .map { requireNotNull(it.probePid) }
                        .distinct()
                        .forEach(::put)
                },
            ).put("dnsProbeUid", observation.testUid)
            .put("facts", semanticFacts)
    publishPrivateJson(context, evidence.receiptFile, receipt)
}

private const val VirtualDnsAddressForEvidence = "198.18.0.53"

private fun publishPrivateJson(
    context: Context,
    name: String,
    value: JSONObject,
) {
    val temporary = File(context.filesDir, "$name.tmp")
    val destination = File(context.filesDir, name)
    check(!destination.exists()) { "Network evidence output already exists" }
    context.openFileOutput(temporary.name, Context.MODE_PRIVATE).use { output ->
        output.write((value.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        output.fd.sync()
    }
    enforceOwnerOnlyMode(temporary)
    check(temporary.renameTo(destination)) { "Could not atomically publish network evidence output" }
}

private fun enforceOwnerOnlyMode(file: File) {
    Os.chmod(file.absolutePath, 0x180)
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
    require(Sha256Regex.matches(facts.dnsQuerySha256))
    require(facts.postReadyDnsEventCount == 1)
    require(facts.txPackets > 0 && facts.rxPackets > 0)

    val receipt =
        JSONObject()
            .put("version", ReceiptVersion)
            .put("status", "PASS")
            .put("gateId", NetworkEvidenceStartupGateId)
            .put("kind", NetworkEvidenceStartupKind)
            .put("selector", NetworkEvidenceStartupSelector)
            .put("semanticRule", NetworkEvidenceStartupRule)
            .put("correlationId", evidence.correlationId)
            .put("sourceSha", evidence.sourceSha)
            .put("clientArtifactSha256", evidence.clientArtifactSha256)
            .put("testArtifactSha256", evidence.testArtifactSha256)
            .put("fixtureIdentitySha256", evidence.fixtureIdentitySha256)
            .put("actionMarkerSha256", evidence.actionMarkerSha256)
            .put("outcomeMarkerSha256", evidence.outcomeMarkerSha256)
            .put("querySetSha256", networkEvidenceQuerySetSha256(NetworkEvidenceStartupGateId, facts.dnsQuerySha256))
            .put("startedAtElapsedRealtimeMs", facts.startedAtElapsedRealtimeMs)
            .put("finishedAtElapsedRealtimeMs", facts.finishedAtElapsedRealtimeMs)
            .put("actionMarkerAtElapsedRealtimeMs", facts.actionMarkerAtElapsedRealtimeMs)
            .put("outcomeMarkerAtElapsedRealtimeMs", facts.outcomeMarkerAtElapsedRealtimeMs)
            .put("appUid", facts.appUid)
            .put("testUid", facts.testUid)
            .put("actionMarkerPid", facts.actionMarkerPid)
            .put("actionMarkerUid", facts.actionMarkerUid)
            .put("outcomeMarkerPid", facts.outcomeMarkerPid)
            .put("outcomeMarkerUid", facts.outcomeMarkerUid)
            .put("dnsProbePids", org.json.JSONArray().put(facts.dnsProbePid))
            .put("dnsProbeUid", facts.dnsProbeUid)
            .put(
                "facts",
                JSONObject()
                    .put("tunFd", facts.tunFd)
                    .put("closedWindowRunningCount", facts.closedWindowRunningCount)
                    .put("preReadyDnsEventCount", facts.preReadyDnsEventCount)
                    .put("startupWindowAssertionElapsedMs", facts.startupWindowAssertionElapsedMs)
                    .put("dnsRcode", facts.dnsRcode)
                    .put("dnsQuerySha256", facts.dnsQuerySha256)
                    .put("dnsAnswersExact", facts.dnsAnswersExact)
                    .put("postReadyDnsEventCount", facts.postReadyDnsEventCount)
                    .put("txPackets", facts.txPackets)
                    .put("rxPackets", facts.rxPackets)
                    .put("finalStatus", "Halted"),
            )
    val temporary = File(context.filesDir, "${evidence.receiptFile}.tmp")
    val destination = File(context.filesDir, evidence.receiptFile)
    check(!destination.exists()) { "Network evidence action receipt already exists" }
    context.openFileOutput(temporary.name, Context.MODE_PRIVATE).use { output ->
        output.write((receipt.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        output.fd.sync()
    }
    enforceOwnerOnlyMode(temporary)
    check(temporary.renameTo(destination)) { "Could not atomically publish network evidence action receipt" }
}

internal fun networkEvidenceDnsQuerySha256(queryHost: String): String {
    require(queryHost.isNotBlank())
    return MessageDigest
        .getInstance("SHA-256")
        .digest("ripdpi:startup-dns-query:v1:${queryHost.lowercase()}".toByteArray(StandardCharsets.US_ASCII))
        .toHex()
}

internal fun networkEvidenceDnsQuerySha256V1(queryHost: String): String {
    require(queryHost.isNotBlank())
    return MessageDigest
        .getInstance("SHA-256")
        .digest("ripdpi:dns-evidence-query:v1:${queryHost.lowercase()}".toByteArray(StandardCharsets.US_ASCII))
        .toHex()
}

private fun networkEvidenceEndpointSha256(
    protocol: String,
    endpoint: String,
): String = networkEvidenceValueSha256("resolver", "$protocol:$endpoint")

/**
 * Mirrors the oracle's `_resolver_endpoint_sha256`, which digests the raw packed
 * destination address followed by a big-endian port.
 *
 * Deliberately not [networkEvidenceEndpointSha256]: that one shares this domain
 * string but digests the text form `"dot:host:port"`. The text form is only ever
 * compared against a fixture transcript, while the six packet-verified DNS gates
 * are compared against what the capture actually observed, so they need the byte
 * form or the oracle rejects the receipt.
 */
internal fun networkEvidencePacketResolverSha256(
    host: String,
    port: Int,
): String {
    require(port in 1..65535) { "Resolver port is out of range: $port" }
    val address = InetAddresses.parseNumericAddress(host).address
    require(address.size == 4 || address.size == 16) { "Unsupported resolver address width" }
    val payload =
        ByteBuffer
            .allocate(address.size + 2)
            .put(address)
            .putShort(port.toShort())
            .array()
    return MessageDigest
        .getInstance("SHA-256")
        .digest("ripdpi:dns-evidence-resolver:v1:".toByteArray(StandardCharsets.US_ASCII) + payload)
        .toHex()
}

/** Mirrors the oracle's packet-bound bootstrap allowlist digest. */
private fun networkEvidenceResolverSetSha256(resolverSha256: String): String {
    require(Sha256Regex.matches(resolverSha256))
    return MessageDigest
        .getInstance("SHA-256")
        .digest(
            "ripdpi:dns-evidence-resolver-set:v1:$resolverSha256".toByteArray(StandardCharsets.US_ASCII),
        ).toHex()
}

internal fun networkEvidenceValueSha256(
    domain: String,
    value: String,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest("ripdpi:dns-evidence-$domain:v1:$value".toByteArray(StandardCharsets.UTF_8))
        .toHex()

private fun networkEvidenceCanonicalJson(value: Any?): String =
    when (value) {
        null, JSONObject.NULL -> {
            "null"
        }

        is JSONObject -> {
            value
                .keys()
                .asSequence()
                .toList()
                .sorted()
                .joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
                    "${JSONObject.quote(key)}:${networkEvidenceCanonicalJson(value.get(key))}"
                }
        }

        is JSONArray -> {
            (0 until value.length()).joinToString(separator = ",", prefix = "[", postfix = "]") { index ->
                networkEvidenceCanonicalJson(value.get(index))
            }
        }

        is String -> {
            JSONObject.quote(value)
        }

        is Boolean -> {
            value.toString()
        }

        is Number -> {
            JSONObject.numberToString(value)
        }

        else -> {
            error("Unsupported network evidence JSON value ${value::class.java.name}")
        }
    }

internal fun networkEvidenceQuerySetSha256(
    gateId: String,
    querySha256: String,
): String {
    require(Sha256Regex.matches(querySha256))
    val queryField =
        when (gateId) {
            NetworkEvidenceStartupGateId -> "dnsQuerySha256"
            "dns-virtual-vpn-resolver" -> "virtualQuerySha256"
            "dns-proxied-through-tunnelled-resolver" -> "proxiedQuerySha256"
            "dns-allowlisted-bootstrap-resolution" -> "bootstrapQuerySha256"
            "dns-no-isp-fallback-on-encrypted-resolver-outage" -> "outageQuerySha256"
            "dns-core-crash-behavior" -> "crashQuerySha256"
            "dns-android-private-dns-conflict" -> "conflictQuerySha256"
            else -> error("Unsupported network evidence query-set gate $gateId")
        }
    return networkEvidenceQuerySetSha256(gateId, mapOf(queryField to querySha256))
}

internal fun networkEvidenceQuerySetSha256(
    gateId: String,
    queryDigests: Map<String, String>,
): String {
    require(queryDigests.isNotEmpty())
    require(queryDigests.values.all(Sha256Regex::matches))
    val queries =
        queryDigests.entries.sortedBy { it.key }.joinToString(separator = ",") { (field, digest) ->
            "[${JSONObject.quote(field)},${JSONObject.quote(digest)}]"
        }
    val canonical = "{\"gateId\":${JSONObject.quote(gateId)},\"queries\":[$queries]}"
    return MessageDigest
        .getInstance("SHA-256")
        .digest("ripdpi:network-evidence-query-set:v1:$canonical".toByteArray(StandardCharsets.US_ASCII))
        .toHex()
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
