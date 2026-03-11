package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proto.StrategyTcpStep
import com.poyka.ripdpi.proto.StrategyUdpStep
import kotlinx.serialization.Serializable

private const val TcpSection = "tcp"
private const val UdpSection = "udp"

@Serializable
enum class TcpChainStepKind(val wireName: String) {
    Split("split"),
    Disorder("disorder"),
    Fake("fake"),
    Oob("oob"),
    Disoob("disoob"),
    TlsRec("tlsrec"),
    ;

    val legacyMethod: String?
        get() =
            when (this) {
                Split -> "split"
                Disorder -> "disorder"
                Fake -> "fake"
                Oob -> "oob"
                Disoob -> "disoob"
                TlsRec -> null
            }

    companion object {
        fun fromWireName(value: String): TcpChainStepKind? = entries.firstOrNull { it.wireName == value.trim().lowercase() }

        fun fromLegacyMethod(value: String): TcpChainStepKind? =
            when (value.trim().lowercase()) {
                "split" -> Split
                "disorder" -> Disorder
                "fake" -> Fake
                "oob" -> Oob
                "disoob" -> Disoob
                else -> null
            }
    }
}

@Serializable
data class TcpChainStepModel(
    val kind: TcpChainStepKind,
    val marker: String,
)

@Serializable
enum class UdpChainStepKind(val wireName: String) {
    FakeBurst("fake_burst"),
    ;

    companion object {
        fun fromWireName(value: String): UdpChainStepKind? = entries.firstOrNull { it.wireName == value.trim().lowercase() }
    }
}

@Serializable
data class UdpChainStepModel(
    val count: Int,
    val kind: UdpChainStepKind = UdpChainStepKind.FakeBurst,
)

@Serializable
data class StrategyChainSet(
    val tcpSteps: List<TcpChainStepModel> = emptyList(),
    val udpSteps: List<UdpChainStepModel> = emptyList(),
)

fun AppSettings.effectiveTcpChainSteps(): List<TcpChainStepModel> =
    if (tcpChainStepsCount > 0) {
        tcpChainStepsList.mapNotNull { it.toModelOrNull() }
    } else {
        synthesizeLegacyTcpChain()
    }

fun AppSettings.effectiveUdpChainSteps(): List<UdpChainStepModel> =
    if (udpChainStepsCount > 0) {
        udpChainStepsList.mapNotNull { it.toModelOrNull() }
    } else if (udpFakeCount > 0) {
        listOf(UdpChainStepModel(count = udpFakeCount))
    } else {
        emptyList()
    }

fun AppSettings.effectiveChainSummary(): String = formatChainSummary(effectiveTcpChainSteps(), effectiveUdpChainSteps())

fun formatChainSummary(
    tcpSteps: List<TcpChainStepModel>,
    udpSteps: List<UdpChainStepModel>,
): String =
    listOfNotNull(
        tcpSteps.takeIf { it.isNotEmpty() }?.joinToString(prefix = "tcp: ", separator = " -> ") {
            "${it.kind.wireName}(${normalizeTcpMarker(it)})"
        },
        udpSteps.takeIf { it.isNotEmpty() }?.joinToString(prefix = "udp: ", separator = " -> ") {
            "${it.kind.wireName}(${it.count.coerceAtLeast(0)})"
        },
    ).ifEmpty {
        listOf("tcp: none")
    }.joinToString(" | ")

fun formatStrategyChainDsl(
    tcpSteps: List<TcpChainStepModel>,
    udpSteps: List<UdpChainStepModel>,
): String {
    val lines = mutableListOf<String>()
    if (tcpSteps.isNotEmpty()) {
        lines += "[tcp]"
        lines += tcpSteps.map { "${it.kind.wireName} ${normalizeTcpMarker(it)}" }
    }
    if (udpSteps.isNotEmpty()) {
        if (lines.isNotEmpty()) {
            lines += ""
        }
        lines += "[udp]"
        lines += udpSteps.map { "${it.kind.wireName} ${it.count.coerceAtLeast(0)}" }
    }
    return lines.joinToString("\n")
}

fun primaryTcpChainStep(tcpSteps: List<TcpChainStepModel>): TcpChainStepModel? =
    tcpSteps.firstOrNull { it.kind != TcpChainStepKind.TlsRec }

fun tlsRecTcpChainStep(tcpSteps: List<TcpChainStepModel>): TcpChainStepModel? =
    tcpSteps.firstOrNull { it.kind == TcpChainStepKind.TlsRec }

fun legacyDesyncMethod(tcpSteps: List<TcpChainStepModel>): String =
    primaryTcpChainStep(tcpSteps)?.kind?.legacyMethod ?: "none"

fun parseStrategyChainDsl(source: String): Result<StrategyChainSet> =
    runCatching {
        val tcpSteps = mutableListOf<TcpChainStepModel>()
        val udpSteps = mutableListOf<UdpChainStepModel>()
        var section = TcpSection

        source.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) {
                return@forEachIndexed
            }
            when (line.lowercase()) {
                "[tcp]" -> {
                    section = TcpSection
                    return@forEachIndexed
                }

                "[udp]" -> {
                    section = UdpSection
                    return@forEachIndexed
                }
            }

            val parts = line.split(Regex("\\s+"), limit = 2)
            require(parts.size == 2) { "Invalid chain step on line ${index + 1}" }
            when (section) {
                TcpSection -> {
                    val kind = TcpChainStepKind.fromWireName(parts[0])
                        ?: error("Unknown TCP step '${parts[0]}' on line ${index + 1}")
                    val marker = normalizeTcpMarker(kind, parts[1])
                    require(isValidOffsetExpression(marker)) { "Invalid marker on line ${index + 1}" }
                    tcpSteps += TcpChainStepModel(kind = kind, marker = marker)
                }

                UdpSection -> {
                    val kind = UdpChainStepKind.fromWireName(parts[0])
                        ?: error("Unknown UDP step '${parts[0]}' on line ${index + 1}")
                    val count = parts[1].toIntOrNull() ?: error("Invalid UDP count on line ${index + 1}")
                    require(count >= 0) { "Invalid UDP count on line ${index + 1}" }
                    udpSteps += UdpChainStepModel(kind = kind, count = count)
                }

                else -> error("Unknown chain section '$section'")
            }
        }

        validateTcpChain(tcpSteps)
        StrategyChainSet(tcpSteps = tcpSteps, udpSteps = udpSteps)
    }

fun AppSettings.Builder.setStrategyChains(
    tcpSteps: List<TcpChainStepModel>,
    udpSteps: List<UdpChainStepModel>,
): AppSettings.Builder =
    apply {
        clearTcpChainSteps()
        tcpSteps.forEach { addTcpChainSteps(it.toProto()) }
        clearUdpChainSteps()
        udpSteps.forEach { addUdpChainSteps(it.toProto()) }
        projectLegacyChainFields(tcpSteps, udpSteps)
    }

fun AppSettings.Builder.projectLegacyChainFields(
    tcpSteps: List<TcpChainStepModel>,
    udpSteps: List<UdpChainStepModel>,
): AppSettings.Builder =
    apply {
        val primaryTcpStep = tcpSteps.firstOrNull { it.kind != TcpChainStepKind.TlsRec }
        val tlsRecStep = tcpSteps.firstOrNull { it.kind == TcpChainStepKind.TlsRec }

        setDesyncMethod(primaryTcpStep?.kind?.legacyMethod ?: "none")
        setSplitMarker(primaryTcpStep?.let(::normalizeTcpMarker) ?: DefaultSplitMarker)
        setSplitPosition(0)
        setSplitAtHost(false)
        setTlsrecEnabled(tlsRecStep != null)
        setTlsrecMarker(tlsRecStep?.let(::normalizeTcpMarker) ?: DefaultTlsRecordMarker)
        setTlsrecPosition(0)
        setTlsrecAtSni(false)
        setUdpFakeCount(udpSteps.sumOf { it.count.coerceAtLeast(0) })
    }

fun AppSettings.Builder.setTcpChainStepsCompat(steps: List<TcpChainStepModel>): AppSettings.Builder =
    setStrategyChains(steps, emptyList())

fun AppSettings.Builder.setUdpChainStepsCompat(steps: List<UdpChainStepModel>): AppSettings.Builder =
    setStrategyChains(emptyList(), steps)

fun AppSettings.Builder.setRawStrategyChainDsl(source: String): AppSettings.Builder {
    val parsed = parseStrategyChainDsl(source).getOrThrow()
    return setStrategyChains(parsed.tcpSteps, parsed.udpSteps)
}

private fun AppSettings.synthesizeLegacyTcpChain(): List<TcpChainStepModel> {
    val steps = mutableListOf<TcpChainStepModel>()
    if (tlsrecEnabled) {
        steps += TcpChainStepModel(TcpChainStepKind.TlsRec, effectiveTlsRecordMarker())
    }
    val method = desyncMethod.ifEmpty { AppSettingsSerializer.defaultValue.desyncMethod.ifEmpty { "disorder" } }
    val kind = TcpChainStepKind.fromLegacyMethod(method)
    if (kind != null) {
        steps += TcpChainStepModel(kind, effectiveSplitMarker())
    }
    return steps
}

private fun StrategyTcpStep.toModelOrNull(): TcpChainStepModel? {
    val kind = TcpChainStepKind.fromWireName(kind) ?: return null
    return TcpChainStepModel(kind = kind, marker = normalizeTcpMarker(kind, marker))
}

private fun StrategyUdpStep.toModelOrNull(): UdpChainStepModel? {
    val resolvedKind = UdpChainStepKind.fromWireName(kind) ?: return null
    return UdpChainStepModel(kind = resolvedKind, count = count)
}

private fun TcpChainStepModel.toProto(): StrategyTcpStep =
    StrategyTcpStep
        .newBuilder()
        .setKind(kind.wireName)
        .setMarker(normalizeTcpMarker(this))
        .build()

private fun UdpChainStepModel.toProto(): StrategyUdpStep =
    StrategyUdpStep
        .newBuilder()
        .setKind(kind.wireName)
        .setCount(count.coerceAtLeast(0))
        .build()

private fun validateTcpChain(steps: List<TcpChainStepModel>) {
    var sawSendStep = false
    steps.forEach { step ->
        when (step.kind) {
            TcpChainStepKind.TlsRec -> require(!sawSendStep) { "tlsrec must be declared before tcp send steps" }
            else -> sawSendStep = true
        }
    }
}

private fun normalizeTcpMarker(step: TcpChainStepModel): String = normalizeTcpMarker(step.kind, step.marker)

private fun normalizeTcpMarker(
    kind: TcpChainStepKind,
    marker: String,
): String {
    val defaultValue = if (kind == TcpChainStepKind.TlsRec) DefaultTlsRecordMarker else DefaultSplitMarker
    return normalizeOffsetExpression(marker, defaultValue)
}
