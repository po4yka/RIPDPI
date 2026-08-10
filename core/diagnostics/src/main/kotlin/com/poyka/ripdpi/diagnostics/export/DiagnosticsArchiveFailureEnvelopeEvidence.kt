package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.DeveloperFailureCategory
import com.poyka.ripdpi.diagnostics.DeveloperFailureEnvelopeEvidence
import com.poyka.ripdpi.diagnostics.DeveloperFailureFactEvidence
import com.poyka.ripdpi.diagnostics.DeveloperFailureFactField
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.DnsObservationStatus
import com.poyka.ripdpi.diagnostics.EndpointProbeStatus
import com.poyka.ripdpi.diagnostics.HttpProbeStatus
import com.poyka.ripdpi.diagnostics.ObservationFact
import com.poyka.ripdpi.diagnostics.QuicProbeStatus
import com.poyka.ripdpi.diagnostics.StrategyProbeProtocol
import com.poyka.ripdpi.diagnostics.StrategyProbeStatus
import com.poyka.ripdpi.diagnostics.TcpProbeStatus
import com.poyka.ripdpi.diagnostics.ThroughputProbeStatus
import com.poyka.ripdpi.diagnostics.TlsProbeStatus
import com.poyka.ripdpi.diagnostics.TransportFailureKind

internal fun buildDeveloperFailureEnvelopeEvidence(
    stage: DiagnosticsArchiveCompositeStageSelection,
): DeveloperFailureEnvelopeEvidence? {
    val summary = stage.stageSummary
    if (summary.status != DiagnosticsHomeCompositeStageStatus.FAILED) return null
    val facts =
        stage.report
            ?.observations
            .orEmpty()
            .flatMapIndexed(::collectTypedFailureFacts)
            .distinct()
    return DeveloperFailureEnvelopeEvidence(
        stageKey = summary.stageKey,
        facts = facts,
    )
}

private fun collectTypedFailureFacts(
    observationIndex: Int,
    observation: ObservationFact,
): List<DeveloperFailureFactEvidence> =
    buildList {
        addDnsAndTcpFailures(observationIndex, observation)
        addDomainFailures(observationIndex, observation)
        addQuicFailures(observationIndex, observation)
        addServiceFailures(observationIndex, observation)
        addCircumventionFailures(observationIndex, observation)
        addStrategyFailures(observationIndex, observation)
        addThroughputFailures(observationIndex, observation)
    }

private fun MutableList<DeveloperFailureFactEvidence>.addDnsAndTcpFailures(
    observationIndex: Int,
    observation: ObservationFact,
) {
    observation.dns?.takeIf { it.status.isFailure() }?.let { dns ->
        addFact(DeveloperFailureCategory.DNS, observationIndex, DeveloperFailureFactField.DNS_STATUS, dns.status)
    }
    observation.tcp?.takeIf { it.status.isFailure() }?.let { tcp ->
        addFact(DeveloperFailureCategory.TCP, observationIndex, DeveloperFailureFactField.TCP_STATUS, tcp.status)
    }
}

private fun MutableList<DeveloperFailureFactEvidence>.addDomainFailures(
    observationIndex: Int,
    observation: ObservationFact,
) {
    observation.domain?.let { domain ->
        addTransportFailure(
            DeveloperFailureCategory.TCP,
            observationIndex,
            DeveloperFailureFactField.DOMAIN_TRANSPORT_FAILURE,
            domain.transportFailure,
        )
        addTlsFailure(observationIndex, DeveloperFailureFactField.DOMAIN_TLS13_STATUS, domain.tls13Status)
        addTlsFailure(observationIndex, DeveloperFailureFactField.DOMAIN_TLS12_STATUS, domain.tls12Status)
        addTlsFailure(observationIndex, DeveloperFailureFactField.DOMAIN_TLS_ECH_STATUS, domain.tlsEchStatus)
        addHttpFailure(observationIndex, DeveloperFailureFactField.DOMAIN_HTTP_STATUS, domain.httpStatus)
    }
}

private fun MutableList<DeveloperFailureFactEvidence>.addQuicFailures(
    observationIndex: Int,
    observation: ObservationFact,
) {
    observation.quic?.let { quic ->
        if (quic.status.isFailure()) {
            addFact(DeveloperFailureCategory.QUIC, observationIndex, DeveloperFailureFactField.QUIC_STATUS, quic.status)
        }
        addTransportFailure(
            DeveloperFailureCategory.QUIC,
            observationIndex,
            DeveloperFailureFactField.QUIC_TRANSPORT_FAILURE,
            quic.transportFailure,
        )
    }
}

private fun MutableList<DeveloperFailureFactEvidence>.addServiceFailures(
    observationIndex: Int,
    observation: ObservationFact,
) {
    observation.service?.let { service ->
        addHttpFailure(observationIndex, DeveloperFailureFactField.SERVICE_BOOTSTRAP_STATUS, service.bootstrapStatus)
        addHttpFailure(observationIndex, DeveloperFailureFactField.SERVICE_MEDIA_STATUS, service.mediaStatus)
        addEndpointFailure(observationIndex, DeveloperFailureFactField.SERVICE_ENDPOINT_STATUS, service.endpointStatus)
        addTransportFailure(
            DeveloperFailureCategory.TCP,
            observationIndex,
            DeveloperFailureFactField.SERVICE_ENDPOINT_FAILURE,
            service.endpointFailure,
        )
        if (service.quicStatus.isFailure()) {
            addFact(
                DeveloperFailureCategory.QUIC,
                observationIndex,
                DeveloperFailureFactField.SERVICE_QUIC_STATUS,
                service.quicStatus,
            )
        }
        addTransportFailure(
            DeveloperFailureCategory.QUIC,
            observationIndex,
            DeveloperFailureFactField.SERVICE_QUIC_FAILURE,
            service.quicFailure,
        )
    }
}

private fun MutableList<DeveloperFailureFactEvidence>.addCircumventionFailures(
    observationIndex: Int,
    observation: ObservationFact,
) {
    observation.circumvention?.let { circumvention ->
        addHttpFailure(
            observationIndex,
            DeveloperFailureFactField.CIRCUMVENTION_BOOTSTRAP_STATUS,
            circumvention.bootstrapStatus,
        )
        addEndpointFailure(
            observationIndex,
            DeveloperFailureFactField.CIRCUMVENTION_HANDSHAKE_STATUS,
            circumvention.handshakeStatus,
        )
        addTransportFailure(
            DeveloperFailureCategory.TCP,
            observationIndex,
            DeveloperFailureFactField.CIRCUMVENTION_HANDSHAKE_FAILURE,
            circumvention.handshakeFailure,
        )
    }
}

private fun MutableList<DeveloperFailureFactEvidence>.addStrategyFailures(
    observationIndex: Int,
    observation: ObservationFact,
) {
    observation.strategy?.let { strategy ->
        val category = strategy.protocol.failureCategory()
        addTransportFailure(
            category,
            observationIndex,
            DeveloperFailureFactField.STRATEGY_TRANSPORT_FAILURE,
            strategy.transportFailure,
        )
        addTlsFailure(observationIndex, DeveloperFailureFactField.STRATEGY_TLS_ECH_STATUS, strategy.tlsEchStatus)
        if (strategy.status in setOf(StrategyProbeStatus.FAILED, StrategyProbeStatus.PARTIAL)) {
            addFact(category, observationIndex, DeveloperFailureFactField.STRATEGY_STATUS, strategy.status)
        }
    }
}

private fun MutableList<DeveloperFailureFactEvidence>.addThroughputFailures(
    observationIndex: Int,
    observation: ObservationFact,
) {
    observation.throughput?.takeIf { it.status == ThroughputProbeStatus.HTTP_UNREACHABLE }?.let { throughput ->
        addFact(
            DeveloperFailureCategory.HTTP,
            observationIndex,
            DeveloperFailureFactField.THROUGHPUT_STATUS,
            throughput.status,
        )
    }
}

private fun MutableList<DeveloperFailureFactEvidence>.addFact(
    category: DeveloperFailureCategory,
    observationIndex: Int,
    field: DeveloperFailureFactField,
    value: Enum<*>,
) {
    add(DeveloperFailureFactEvidence(category, observationIndex, field, value))
}

private fun MutableList<DeveloperFailureFactEvidence>.addTransportFailure(
    category: DeveloperFailureCategory,
    observationIndex: Int,
    field: DeveloperFailureFactField,
    failure: TransportFailureKind,
) {
    if (failure != TransportFailureKind.NONE) addFact(category, observationIndex, field, failure)
}

private fun MutableList<DeveloperFailureFactEvidence>.addTlsFailure(
    observationIndex: Int,
    field: DeveloperFailureFactField,
    status: TlsProbeStatus,
) {
    if (status !in setOf(TlsProbeStatus.OK, TlsProbeStatus.NOT_RUN)) {
        addFact(DeveloperFailureCategory.TLS, observationIndex, field, status)
    }
}

private fun MutableList<DeveloperFailureFactEvidence>.addHttpFailure(
    observationIndex: Int,
    field: DeveloperFailureFactField,
    status: HttpProbeStatus,
) {
    if (status !in setOf(HttpProbeStatus.OK, HttpProbeStatus.NOT_RUN)) {
        addFact(DeveloperFailureCategory.HTTP, observationIndex, field, status)
    }
}

private fun MutableList<DeveloperFailureFactEvidence>.addEndpointFailure(
    observationIndex: Int,
    field: DeveloperFailureFactField,
    status: EndpointProbeStatus,
) {
    if (status in setOf(EndpointProbeStatus.FAILED, EndpointProbeStatus.BLOCKED)) {
        addFact(DeveloperFailureCategory.TCP, observationIndex, field, status)
    }
}

private fun DnsObservationStatus.isFailure(): Boolean =
    this !in
        setOf(
            DnsObservationStatus.MATCH,
            DnsObservationStatus.EXPECTED_MISMATCH,
            DnsObservationStatus.COMPATIBLE_DIVERGENCE,
        )

private fun TcpProbeStatus.isFailure(): Boolean = this !in setOf(TcpProbeStatus.OK, TcpProbeStatus.WHITELIST_SNI_OK)

private fun QuicProbeStatus.isFailure(): Boolean =
    this !in setOf(QuicProbeStatus.INITIAL_RESPONSE, QuicProbeStatus.RESPONSE, QuicProbeStatus.NOT_RUN)

private fun StrategyProbeProtocol.failureCategory(): DeveloperFailureCategory =
    when (this) {
        StrategyProbeProtocol.QUIC -> DeveloperFailureCategory.QUIC

        StrategyProbeProtocol.HTTP,
        StrategyProbeProtocol.HTTPS,
        -> DeveloperFailureCategory.HTTP

        StrategyProbeProtocol.CANDIDATE,
        StrategyProbeProtocol.BASELINE,
        -> DeveloperFailureCategory.TCP
    }
