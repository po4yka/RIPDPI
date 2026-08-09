package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.CircumventionObservationFact
import com.poyka.ripdpi.diagnostics.DnsObservationFact
import com.poyka.ripdpi.diagnostics.DnsObservationStatus
import com.poyka.ripdpi.diagnostics.DomainObservationFact
import com.poyka.ripdpi.diagnostics.EndpointProbeStatus
import com.poyka.ripdpi.diagnostics.HttpProbeStatus
import com.poyka.ripdpi.diagnostics.ObservationFact
import com.poyka.ripdpi.diagnostics.QuicObservationFact
import com.poyka.ripdpi.diagnostics.QuicProbeStatus
import com.poyka.ripdpi.diagnostics.ServiceObservationFact
import com.poyka.ripdpi.diagnostics.TcpObservationFact
import com.poyka.ripdpi.diagnostics.TcpProbeStatus
import com.poyka.ripdpi.diagnostics.TlsProbeStatus
import com.poyka.ripdpi.diagnostics.TransportFailureKind
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

internal class DiagnosticsArchiveProtocolMilestonesEntryBuilder(
    private val json: Json,
) {
    fun buildRoot(
        selection: DiagnosticsArchiveSelection,
        report: EngineScanReportWire?,
    ) = build(
        name = "protocol-milestones.jsonl",
        sessionId = selection.primarySession?.id,
        profileId = selection.primarySession?.profileId,
        report = report,
    )

    fun buildStage(
        prefix: String,
        stage: DiagnosticsArchiveCompositeStageSelection,
        report: EngineScanReportWire?,
    ) = build(
        name = "$prefix/protocol-milestones.jsonl",
        sessionId = stage.session?.id,
        profileId = stage.session?.profileId,
        report = report,
    )

    fun build(
        name: String,
        sessionId: String?,
        profileId: String?,
        report: EngineScanReportWire?,
    ): DiagnosticsArchiveEntry =
        DiagnosticsArchiveEntry(
            name = name,
            bytes = buildJsonLines(sessionId, profileId, report).toByteArray(),
        )

    private fun buildJsonLines(
        sessionId: String?,
        profileId: String?,
        report: EngineScanReportWire?,
    ): String =
        report
            ?.observations
            .orEmpty()
            .flatMapIndexed { index, observation ->
                buildObservationRecords(
                    sessionId = sessionId,
                    profileId = profileId,
                    observationIndex = index + 1,
                    observation = observation,
                )
            }.mapIndexed { index, record -> record.copy(sequence = index + 1) }
            .joinToString(separator = "", postfix = "") { record ->
                json.encodeToJsonElement(ProtocolMilestoneArchiveRecord.serializer(), record).toString() + "\n"
            }

    private fun buildObservationRecords(
        sessionId: String?,
        profileId: String?,
        observationIndex: Int,
        observation: ObservationFact,
    ): List<ProtocolMilestoneArchiveRecord> {
        val evidencePrefix = "report.json#/observations/${observationIndex - 1}"
        return buildList {
            observation.dns?.let { dns ->
                addDnsMilestone(dns, evidencePrefix, recordFactory(sessionId, profileId, observationIndex, observation))
            }
            observation.tcp?.let { tcp ->
                addTcpMilestones(
                    tcp,
                    evidencePrefix,
                    recordFactory(sessionId, profileId, observationIndex, observation),
                )
            }
            observation.domain?.let { domain ->
                addDomainMilestones(
                    sessionId,
                    profileId,
                    observationIndex,
                    observation,
                    domain,
                    evidencePrefix,
                )
            }
            observation.quic?.let { quic ->
                addQuicMilestone(
                    quic,
                    "$evidencePrefix/quic/status",
                    recordFactory(sessionId, profileId, observationIndex, observation),
                )
            }
            observation.service?.let { service ->
                addServiceMilestones(
                    service,
                    evidencePrefix,
                    recordFactory(sessionId, profileId, observationIndex, observation),
                )
            }
            observation.circumvention?.let { circumvention ->
                addCircumventionMilestones(
                    circumvention,
                    evidencePrefix,
                    recordFactory(sessionId, profileId, observationIndex, observation),
                )
            }
        }
    }

    private fun MutableList<ProtocolMilestoneArchiveRecord>.addDomainMilestones(
        sessionId: String?,
        profileId: String?,
        observationIndex: Int,
        observation: ObservationFact,
        domain: DomainObservationFact,
        evidencePrefix: String,
    ) {
        addTlsMilestone(sessionId, profileId, observationIndex, observation, domain.tls13Status, true)
        addTlsMilestone(sessionId, profileId, observationIndex, observation, domain.tls12Status, false)
        val makeRecord = recordFactory(sessionId, profileId, observationIndex, observation)
        if (domain.tlsEchStatus != TlsProbeStatus.NOT_RUN) {
            add(
                makeRecord(
                    ProtocolMilestoneArchiveProtocol.TLS_ECH,
                    ProtocolMilestoneArchiveKind.TLS_ECH_NEGOTIATION,
                    domain.tlsEchStatus.toArchiveStatus(),
                    domain.tlsEchStatus.name,
                    "$evidencePrefix/domain/tlsEchStatus",
                    domain.transportFailure.withoutNone(),
                ),
            )
        }
        addHttpMilestone(
            domain.httpStatus,
            ProtocolMilestoneArchiveKind.HTTP_RESPONSE,
            "$evidencePrefix/domain/httpStatus",
            makeRecord,
        )
    }

    private fun MutableList<ProtocolMilestoneArchiveRecord>.addQuicMilestone(
        quic: QuicObservationFact,
        evidenceRef: String,
        makeRecord: RecordFactory,
    ) {
        if (quic.status == QuicProbeStatus.NOT_RUN) return
        add(
            makeRecord(
                ProtocolMilestoneArchiveProtocol.QUIC,
                ProtocolMilestoneArchiveKind.QUIC_RESPONSE,
                quic.status.toArchiveStatus(),
                quic.status.name,
                evidenceRef,
                quic.transportFailure.withoutNone(),
            ),
        )
    }

    private fun MutableList<ProtocolMilestoneArchiveRecord>.addDnsMilestone(
        dns: DnsObservationFact,
        evidencePrefix: String,
        makeRecord: RecordFactory,
    ) {
        add(
            makeRecord(
                ProtocolMilestoneArchiveProtocol.DNS,
                ProtocolMilestoneArchiveKind.DNS_COMPARISON,
                dns.status.toArchiveStatus(),
                dns.status.name,
                "$evidencePrefix/dns/status",
                null,
            ),
        )
    }

    private fun MutableList<ProtocolMilestoneArchiveRecord>.addTcpMilestones(
        tcp: TcpObservationFact,
        evidencePrefix: String,
        makeRecord: RecordFactory,
    ) {
        val evidenceRef = "$evidencePrefix/tcp/status"
        val connectStatus =
            if (tcp.status == TcpProbeStatus.CONNECT_FAILED || tcp.status == TcpProbeStatus.ERROR) {
                ProtocolMilestoneArchiveStatus.FAILED
            } else {
                ProtocolMilestoneArchiveStatus.REACHED
            }
        add(
            makeRecord(
                ProtocolMilestoneArchiveProtocol.TCP,
                ProtocolMilestoneArchiveKind.TCP_CONNECT,
                connectStatus,
                tcp.status.name,
                evidenceRef,
                null,
            ),
        )
        if (connectStatus == ProtocolMilestoneArchiveStatus.REACHED) {
            add(
                makeRecord(
                    ProtocolMilestoneArchiveProtocol.TCP,
                    ProtocolMilestoneArchiveKind.TCP_FLOW,
                    tcp.status.toFlowArchiveStatus(),
                    tcp.status.name,
                    evidenceRef,
                    null,
                ),
            )
        }
    }

    private fun MutableList<ProtocolMilestoneArchiveRecord>.addServiceMilestones(
        service: ServiceObservationFact,
        evidencePrefix: String,
        makeRecord: RecordFactory,
    ) {
        addHttpMilestone(
            service.bootstrapStatus,
            ProtocolMilestoneArchiveKind.HTTP_BOOTSTRAP,
            "$evidencePrefix/service/bootstrapStatus",
            makeRecord,
        )
        addHttpMilestone(
            service.mediaStatus,
            ProtocolMilestoneArchiveKind.HTTP_MEDIA_RESPONSE,
            "$evidencePrefix/service/mediaStatus",
            makeRecord,
        )
        addEndpointMilestone(
            service.endpointStatus,
            ProtocolMilestoneArchiveKind.SERVICE_ENDPOINT,
            service.endpointFailure,
            "$evidencePrefix/service/endpointStatus",
            makeRecord,
        )
        if (service.quicStatus != QuicProbeStatus.NOT_RUN) {
            add(
                makeRecord(
                    ProtocolMilestoneArchiveProtocol.QUIC,
                    ProtocolMilestoneArchiveKind.QUIC_RESPONSE,
                    service.quicStatus.toArchiveStatus(),
                    service.quicStatus.name,
                    "$evidencePrefix/service/quicStatus",
                    service.quicFailure.withoutNone(),
                ),
            )
        }
    }

    private fun MutableList<ProtocolMilestoneArchiveRecord>.addCircumventionMilestones(
        circumvention: CircumventionObservationFact,
        evidencePrefix: String,
        makeRecord: RecordFactory,
    ) {
        addHttpMilestone(
            circumvention.bootstrapStatus,
            ProtocolMilestoneArchiveKind.HTTP_BOOTSTRAP,
            "$evidencePrefix/circumvention/bootstrapStatus",
            makeRecord,
        )
        addEndpointMilestone(
            circumvention.handshakeStatus,
            ProtocolMilestoneArchiveKind.CIRCUMVENTION_HANDSHAKE,
            circumvention.handshakeFailure,
            "$evidencePrefix/circumvention/handshakeStatus",
            makeRecord,
        )
    }

    private fun MutableList<ProtocolMilestoneArchiveRecord>.addHttpMilestone(
        status: HttpProbeStatus,
        milestone: ProtocolMilestoneArchiveKind,
        evidenceRef: String,
        makeRecord: RecordFactory,
    ) {
        if (status == HttpProbeStatus.NOT_RUN) return
        add(
            makeRecord(
                ProtocolMilestoneArchiveProtocol.HTTP,
                milestone,
                status.toArchiveStatus(),
                status.name,
                evidenceRef,
                null,
            ),
        )
    }

    private fun MutableList<ProtocolMilestoneArchiveRecord>.addEndpointMilestone(
        status: EndpointProbeStatus,
        milestone: ProtocolMilestoneArchiveKind,
        failureKind: TransportFailureKind,
        evidenceRef: String,
        makeRecord: RecordFactory,
    ) {
        if (status == EndpointProbeStatus.NOT_RUN) return
        add(
            makeRecord(
                ProtocolMilestoneArchiveProtocol.ENDPOINT,
                milestone,
                status.toArchiveStatus(),
                status.name,
                evidenceRef,
                failureKind.withoutNone(),
            ),
        )
    }

    private fun recordFactory(
        sessionId: String?,
        profileId: String?,
        observationIndex: Int,
        observation: ObservationFact,
    ): RecordFactory =
        { protocol, milestone, status, outcome, evidenceRef, failureKind ->
            record(
                sessionId,
                profileId,
                observationIndex,
                observation,
                protocol,
                milestone,
                status,
                outcome,
                evidenceRef,
                failureKind,
            )
        }

    private fun MutableList<ProtocolMilestoneArchiveRecord>.addTlsMilestone(
        sessionId: String?,
        profileId: String?,
        observationIndex: Int,
        observation: ObservationFact,
        status: TlsProbeStatus,
        tls13: Boolean,
    ) {
        if (status == TlsProbeStatus.NOT_RUN) return
        add(
            record(
                sessionId = sessionId,
                profileId = profileId,
                observationIndex = observationIndex,
                observation = observation,
                protocol =
                    if (tls13) {
                        ProtocolMilestoneArchiveProtocol.TLS_1_3
                    } else {
                        ProtocolMilestoneArchiveProtocol.TLS_1_2
                    },
                milestone = ProtocolMilestoneArchiveKind.TLS_HANDSHAKE,
                status = status.toArchiveStatus(),
                outcome = status.name,
                failureKind = observation.domain?.transportFailure?.withoutNone(),
                evidenceRef =
                    "report.json#/observations/${observationIndex - 1}/domain/" +
                        if (tls13) "tls13Status" else "tls12Status",
            ),
        )
    }

    private fun record(
        sessionId: String?,
        profileId: String?,
        observationIndex: Int,
        observation: ObservationFact,
        protocol: ProtocolMilestoneArchiveProtocol,
        milestone: ProtocolMilestoneArchiveKind,
        status: ProtocolMilestoneArchiveStatus,
        outcome: String,
        evidenceRef: String,
        failureKind: TransportFailureKind? = null,
    ) = ProtocolMilestoneArchiveRecord(
        sessionId = sessionId,
        profileId = profileId,
        sequence = 0,
        observationIndex = observationIndex,
        subjectAlias = "observation-$observationIndex",
        observationKind = observation.kind,
        protocol = protocol,
        milestone = milestone,
        status = status,
        outcome = outcome,
        failureKind = failureKind,
        evidenceRef = evidenceRef,
    )
}

private typealias RecordFactory =
    (
        ProtocolMilestoneArchiveProtocol,
        ProtocolMilestoneArchiveKind,
        ProtocolMilestoneArchiveStatus,
        String,
        String,
        TransportFailureKind?,
    ) -> ProtocolMilestoneArchiveRecord

private fun DnsObservationStatus.toArchiveStatus(): ProtocolMilestoneArchiveStatus =
    when (this) {
        DnsObservationStatus.MATCH -> ProtocolMilestoneArchiveStatus.REACHED

        DnsObservationStatus.EXPECTED_MISMATCH,
        DnsObservationStatus.COMPATIBLE_DIVERGENCE,
        DnsObservationStatus.SUSPICIOUS_DIVERGENCE,
        DnsObservationStatus.SINKHOLE_SUBSTITUTION,
        DnsObservationStatus.NXDOMAIN_MISMATCH,
        -> ProtocolMilestoneArchiveStatus.OBSERVED

        DnsObservationStatus.ORACLE_UNAVAILABLE,
        DnsObservationStatus.UDP_BLOCKED,
        DnsObservationStatus.UNAVAILABLE,
        -> ProtocolMilestoneArchiveStatus.FAILED
    }

private fun TcpProbeStatus.toFlowArchiveStatus(): ProtocolMilestoneArchiveStatus =
    when (this) {
        TcpProbeStatus.OK,
        TcpProbeStatus.WHITELIST_SNI_OK,
        -> ProtocolMilestoneArchiveStatus.REACHED

        TcpProbeStatus.BLOCKED_16KB,
        TcpProbeStatus.FREEZE_AFTER_THRESHOLD,
        TcpProbeStatus.CONNECT_FAILED,
        TcpProbeStatus.ERROR,
        -> ProtocolMilestoneArchiveStatus.FAILED
    }

private fun EndpointProbeStatus.toArchiveStatus(): ProtocolMilestoneArchiveStatus =
    when (this) {
        EndpointProbeStatus.OK -> ProtocolMilestoneArchiveStatus.REACHED

        EndpointProbeStatus.FAILED,
        EndpointProbeStatus.BLOCKED,
        EndpointProbeStatus.NOT_RUN,
        -> ProtocolMilestoneArchiveStatus.FAILED
    }

private fun TlsProbeStatus.toArchiveStatus(): ProtocolMilestoneArchiveStatus =
    when (this) {
        TlsProbeStatus.OK -> ProtocolMilestoneArchiveStatus.REACHED

        TlsProbeStatus.VERSION_SPLIT -> ProtocolMilestoneArchiveStatus.OBSERVED

        TlsProbeStatus.HANDSHAKE_FAILED,
        TlsProbeStatus.CERT_INVALID,
        TlsProbeStatus.NOT_RUN,
        -> ProtocolMilestoneArchiveStatus.FAILED
    }

private fun HttpProbeStatus.toArchiveStatus(): ProtocolMilestoneArchiveStatus =
    when (this) {
        HttpProbeStatus.OK,
        HttpProbeStatus.BLOCKPAGE,
        -> ProtocolMilestoneArchiveStatus.REACHED

        HttpProbeStatus.UNREACHABLE,
        HttpProbeStatus.NOT_RUN,
        -> ProtocolMilestoneArchiveStatus.FAILED
    }

private fun QuicProbeStatus.toArchiveStatus(): ProtocolMilestoneArchiveStatus =
    when (this) {
        QuicProbeStatus.INITIAL_RESPONSE,
        QuicProbeStatus.RESPONSE,
        -> ProtocolMilestoneArchiveStatus.REACHED

        QuicProbeStatus.EMPTY,
        QuicProbeStatus.ERROR,
        QuicProbeStatus.NOT_RUN,
        -> ProtocolMilestoneArchiveStatus.FAILED
    }

private fun TransportFailureKind.withoutNone(): TransportFailureKind? = takeUnless { it == TransportFailureKind.NONE }
