package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.diagnostics.DnsMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

data class RelayUdpPayloadHealthEvidence(
    val measurementKind: String = RelayUdpPayloadMeasurementKind,
    val ceilingLabel: String = RelayUdpPayloadCeilingLabel,
    val overheadAssessment: String = RelayUdpPayloadOverheadAssessment,
    val effectivePathMtuBytes: Int? = null,
    val overallVerdict: String,
    val families: List<RelayUdpPayloadFamilyHealthEvidence>,
) {
    val passed: Boolean
        get() =
            families.any() &&
                families.all(RelayUdpPayloadFamilyHealthEvidence::acknowledgedAcceptanceFloor)

    val failureClass: String?
        get() =
            if (passed) {
                null
            } else {
                families
                    .firstOrNull {
                        !it.acknowledgedAcceptanceFloor()
                    }?.verdict
            }
}

private fun RelayUdpPayloadFamilyHealthEvidence.acknowledgedAcceptanceFloor(): Boolean =
    controlBefore == RelayUdpPayloadControlOutcome.Acknowledged.wireValue &&
        controlAfter == RelayUdpPayloadControlOutcome.Acknowledged.wireValue &&
        (maxAcknowledgedPayloadBytes ?: 0) >= RelayUdpPayloadAcceptanceFloorBytes

data class RelayUdpPayloadFamilyHealthEvidence(
    val family: String,
    val controlBefore: String,
    val controlAfter: String,
    val maxAcknowledgedPayloadBytes: Int?,
    val firstRepeatedFailedPayloadBytes: Int?,
    val attemptCount: Int,
    val verdict: String,
    val ptbObservation: String = RelayUdpPayloadPathSignal.NotObservable.wireValue,
    val fragmentationReassembly: String = RelayUdpPayloadPathSignal.NotObservable.wireValue,
)

enum class RelayUdpPayloadFamily(
    val wireValue: String,
) {
    Ipv4("ipv4"),
    Ipv6("ipv6"),
}

enum class RelayUdpPayloadControlOutcome(
    val wireValue: String,
) {
    Acknowledged("acknowledged"),
    Failed("failed"),
    NotAttempted("not_attempted"),
}

enum class RelayUdpPayloadHealthVerdict(
    val wireValue: String,
) {
    Acknowledged("acknowledged"),
    InconclusiveControlFailed("inconclusive_control_failed"),
    InconclusiveNoPayloadAcknowledged("inconclusive_no_payload_acknowledged"),
    InconclusiveSizeCorrelatedLoss("inconclusive_size_correlated_loss"),
    NotAttempted("not_attempted"),
}

enum class RelayUdpPayloadPathSignal(
    val wireValue: String,
) {
    Observed("observed"),
    NotObservable("not_observable"),
    Unknown("unknown"),
}

internal fun interface RelayUdpPayloadHealthProbe {
    suspend fun probe(
        endpoint: RelayProbeEndpoint,
        families: Set<RelayUdpPayloadFamily>,
        targets: Map<RelayUdpPayloadFamily, InetSocketAddress>,
    ): RelayUdpPayloadHealthEvidence
}

internal data class RelayUdpPayloadProbeAttempt(
    val payloadSizeBytes: Int,
    val acknowledged: Boolean,
)

internal data class RelayUdpPayloadFamilyClassification(
    val controlBefore: RelayUdpPayloadControlOutcome,
    val controlAfter: RelayUdpPayloadControlOutcome,
    val maxAcknowledgedPayloadBytes: Int?,
    val firstRepeatedFailedPayloadBytes: Int?,
    val attemptCount: Int,
    val verdict: RelayUdpPayloadHealthVerdict,
)

internal fun classifyRelayUdpPayloadFamily(
    preControlAcknowledged: Boolean,
    attempts: List<RelayUdpPayloadProbeAttempt>,
    postControlAcknowledged: Boolean?,
): RelayUdpPayloadFamilyClassification {
    val firstRepeatedFailure =
        attempts
            .groupBy(RelayUdpPayloadProbeAttempt::payloadSizeBytes)
            .entries
            .firstOrNull { (_, sizeAttempts) ->
                sizeAttempts.count { !it.acknowledged } >= RepeatedPayloadFailureThreshold
            }?.key
    val maxAcknowledged = attempts.filter(RelayUdpPayloadProbeAttempt::acknowledged).maxOfOrNull { it.payloadSizeBytes }
    val controlBefore =
        if (preControlAcknowledged) {
            RelayUdpPayloadControlOutcome.Acknowledged
        } else {
            RelayUdpPayloadControlOutcome.Failed
        }
    val controlAfter =
        when (postControlAcknowledged) {
            true -> RelayUdpPayloadControlOutcome.Acknowledged
            false -> RelayUdpPayloadControlOutcome.Failed
            null -> RelayUdpPayloadControlOutcome.NotAttempted
        }
    val verdict =
        when {
            !preControlAcknowledged -> RelayUdpPayloadHealthVerdict.InconclusiveControlFailed
            postControlAcknowledged != true -> RelayUdpPayloadHealthVerdict.InconclusiveControlFailed
            firstRepeatedFailure != null -> RelayUdpPayloadHealthVerdict.InconclusiveSizeCorrelatedLoss
            maxAcknowledged != null -> RelayUdpPayloadHealthVerdict.Acknowledged
            else -> RelayUdpPayloadHealthVerdict.InconclusiveNoPayloadAcknowledged
        }
    return RelayUdpPayloadFamilyClassification(
        controlBefore = controlBefore,
        controlAfter = controlAfter,
        maxAcknowledgedPayloadBytes = maxAcknowledged,
        firstRepeatedFailedPayloadBytes = firstRepeatedFailure,
        attemptCount = attempts.size + 1 + if (postControlAcknowledged != null) 1 else 0,
        verdict = verdict,
    )
}

internal class Socks5DnsUdpPayloadHealthProbe internal constructor(
    private val timeoutMillis: Int,
    private val payloadSizesBytes: List<Int>,
    initialTransactionId: Int = SecureRandom().nextInt(DnsTransactionIdUpperBound),
    private val queryFactory: (Int, Int) -> ByteArray = ::sizedDnsQuery,
) : RelayUdpPayloadHealthProbe {
    private val transactionIds = AtomicInteger(initialTransactionId)

    init {
        require(initialTransactionId in 0 until DnsTransactionIdUpperBound) {
            "DNS transaction ID must fit in 16 bits"
        }
    }

    constructor() : this(
        timeoutMillis = UdpPayloadProbeTimeoutMillis,
        payloadSizesBytes = DefaultUdpPayloadLadderBytes,
    )

    /**
     * cancel-safe at the coroutine boundary: every blocking socket operation
     * has a fixed timeout and sockets are closed before returning.
     */
    override suspend fun probe(
        endpoint: RelayProbeEndpoint,
        families: Set<RelayUdpPayloadFamily>,
        targets: Map<RelayUdpPayloadFamily, InetSocketAddress>,
    ): RelayUdpPayloadHealthEvidence =
        try {
            runInterruptible(Dispatchers.IO) { probeBlocking(endpoint, families, targets) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            controlFailedHealthEvidence(families)
        } catch (interrupted: InterruptedIOException) {
            throw interrupted
        } catch (_: IOException) {
            controlFailedHealthEvidence(families)
        }

    private fun probeBlocking(
        endpoint: RelayProbeEndpoint,
        families: Set<RelayUdpPayloadFamily>,
        targets: Map<RelayUdpPayloadFamily, InetSocketAddress>,
    ): RelayUdpPayloadHealthEvidence {
        val familyResults =
            families
                .sortedBy(RelayUdpPayloadFamily::wireValue)
                .map { family ->
                    val target = targets[family] ?: return@map notAttemptedEvidence(family)
                    probeFamilyOrControlFailure(endpoint, family, target)
                }
        return RelayUdpPayloadHealthEvidence(
            overallVerdict = overallVerdict(familyResults),
            families = familyResults,
        )
    }

    private fun probeFamilyOrControlFailure(
        endpoint: RelayProbeEndpoint,
        family: RelayUdpPayloadFamily,
        target: InetSocketAddress,
    ): RelayUdpPayloadFamilyHealthEvidence =
        try {
            probeFamily(endpoint, family, target)
        } catch (_: SocketTimeoutException) {
            controlFailedEvidence(family)
        } catch (interrupted: InterruptedIOException) {
            throw interrupted
        } catch (_: ProtocolException) {
            controlFailedEvidence(family)
        } catch (_: IOException) {
            controlFailedEvidence(family)
        }

    private fun probeFamily(
        endpoint: RelayProbeEndpoint,
        family: RelayUdpPayloadFamily,
        target: InetSocketAddress,
    ): RelayUdpPayloadFamilyHealthEvidence {
        val classification =
            Socket().use { control ->
                control.soTimeout = timeoutMillis
                control.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMillis)
                val input = DataInputStream(control.getInputStream())
                val output = DataOutputStream(control.getOutputStream())
                negotiateLocalSocksAuthentication(endpoint, input, output)
                val udpRelay = openUdpAssociation(control.inetAddress, input, output)
                openUdpProbeSocket(control.localAddress, udpRelay.address).use { udp ->
                    udp.soTimeout = timeoutMillis
                    probePayloadFamily(udp, udpRelay, target)
                }
            }
        return familyEvidence(family, classification)
    }

    private fun probePayloadFamily(
        udp: DatagramSocket,
        udpRelay: InetSocketAddress,
        target: InetSocketAddress,
    ): RelayUdpPayloadFamilyClassification {
        val preControl = sendProbe(udp, udpRelay, target, UdpPayloadControlBytes)
        if (!preControl) {
            return classifyRelayUdpPayloadFamily(
                preControlAcknowledged = false,
                attempts = emptyList(),
                postControlAcknowledged = null,
            )
        }
        val attempts = mutableListOf<RelayUdpPayloadProbeAttempt>()
        for (payloadSize in payloadSizesBytes.normalizedPayloadLadder()) {
            val failedAttempts = recordPayloadAttempts(udp, udpRelay, target, payloadSize, attempts)
            if (failedAttempts >= RepeatedPayloadFailureThreshold) break
        }
        return classifyRelayUdpPayloadFamily(
            preControlAcknowledged = true,
            attempts = attempts,
            postControlAcknowledged = sendProbe(udp, udpRelay, target, UdpPayloadControlBytes),
        )
    }

    private fun recordPayloadAttempts(
        udp: DatagramSocket,
        udpRelay: InetSocketAddress,
        target: InetSocketAddress,
        payloadSize: Int,
        attempts: MutableList<RelayUdpPayloadProbeAttempt>,
    ): Int {
        var failedAttempts = 0
        run {
            repeat(PayloadAttemptsPerSize) {
                val acknowledged = sendProbe(udp, udpRelay, target, payloadSize)
                attempts += RelayUdpPayloadProbeAttempt(payloadSize, acknowledged)
                if (acknowledged) return@run
                failedAttempts += 1
            }
        }
        return failedAttempts
    }

    private fun sendProbe(
        udp: DatagramSocket,
        udpRelay: InetSocketAddress,
        target: InetSocketAddress,
        payloadSizeBytes: Int,
    ): Boolean =
        sendDnsProbePayload(
            udp = udp,
            udpRelay = udpRelay,
            target = target,
            query = queryFactory(payloadSizeBytes, nextTransactionId()),
            timeoutMillis = timeoutMillis,
        )

    private fun nextTransactionId(): Int =
        transactionIds.getAndUpdate { current ->
            if (current == DnsTransactionIdMaximum) 0 else current + 1
        }
}

private fun sizedDnsQuery(
    payloadSizeBytes: Int,
    queryId: Int,
): ByteArray = DnsMessages.query("invalid", transactionId = queryId, payloadSize = payloadSizeBytes)

private fun List<Int>.normalizedPayloadLadder(): List<Int> =
    filter { it in UdpPayloadControlBytes..MaxUdpPayloadProbeBytes }.distinct().sorted()

private fun familyEvidence(
    family: RelayUdpPayloadFamily,
    classification: RelayUdpPayloadFamilyClassification,
): RelayUdpPayloadFamilyHealthEvidence =
    RelayUdpPayloadFamilyHealthEvidence(
        family = family.wireValue,
        controlBefore = classification.controlBefore.wireValue,
        controlAfter = classification.controlAfter.wireValue,
        maxAcknowledgedPayloadBytes = classification.maxAcknowledgedPayloadBytes,
        firstRepeatedFailedPayloadBytes = classification.firstRepeatedFailedPayloadBytes,
        attemptCount = classification.attemptCount,
        verdict = classification.verdict.wireValue,
    )

private fun controlFailedEvidence(family: RelayUdpPayloadFamily): RelayUdpPayloadFamilyHealthEvidence =
    familyEvidence(
        family,
        classifyRelayUdpPayloadFamily(
            preControlAcknowledged = false,
            attempts = emptyList(),
            postControlAcknowledged = null,
        ),
    )

private fun controlFailedHealthEvidence(families: Set<RelayUdpPayloadFamily>): RelayUdpPayloadHealthEvidence =
    RelayUdpPayloadHealthEvidence(
        overallVerdict = RelayUdpPayloadHealthVerdict.InconclusiveControlFailed.wireValue,
        families = families.sortedBy(RelayUdpPayloadFamily::wireValue).map(::controlFailedEvidence),
    )

private fun notAttemptedEvidence(family: RelayUdpPayloadFamily): RelayUdpPayloadFamilyHealthEvidence =
    RelayUdpPayloadFamilyHealthEvidence(
        family = family.wireValue,
        controlBefore = RelayUdpPayloadControlOutcome.NotAttempted.wireValue,
        controlAfter = RelayUdpPayloadControlOutcome.NotAttempted.wireValue,
        maxAcknowledgedPayloadBytes = null,
        firstRepeatedFailedPayloadBytes = null,
        attemptCount = 0,
        verdict = RelayUdpPayloadHealthVerdict.NotAttempted.wireValue,
    )

private fun overallVerdict(families: List<RelayUdpPayloadFamilyHealthEvidence>): String =
    when {
        families.isEmpty() -> {
            RelayUdpPayloadHealthVerdict.NotAttempted.wireValue
        }

        families.all { it.verdict == RelayUdpPayloadHealthVerdict.Acknowledged.wireValue } -> {
            RelayUdpPayloadHealthVerdict.Acknowledged.wireValue
        }

        else -> {
            families.first { it.verdict != RelayUdpPayloadHealthVerdict.Acknowledged.wireValue }.verdict
        }
    }

private const val UdpPayloadProbeTimeoutMillis = 1_500
private const val UdpPayloadControlBytes = 64
private const val MaxUdpPayloadProbeBytes = 1_400
private const val RelayUdpPayloadAcceptanceFloorBytes = 1_232
private const val PayloadAttemptsPerSize = 2
private const val RepeatedPayloadFailureThreshold = 2
private const val DnsTransactionIdBitWidth = 16
private const val DnsTransactionIdUpperBound = 1 shl DnsTransactionIdBitWidth
private const val DnsTransactionIdMaximum = DnsTransactionIdUpperBound - 1
private val DefaultUdpPayloadLadderBytes = listOf(256, 512, 960, 1_232, MaxUdpPayloadProbeBytes)
private const val RelayUdpPayloadMeasurementKind = "relay_egress_udp_payload"
private const val RelayUdpPayloadCeilingLabel = "acknowledged_application_payload_ceiling"
private const val RelayUdpPayloadOverheadAssessment = "not_quantified_variable_encapsulation"
