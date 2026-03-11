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
    HostFake("hostfake"),
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
                HostFake -> "fake"
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
    val midhostMarker: String = "",
    val fakeHostTemplate: String = "",
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
            formatTcpStepSummary(it)
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
        lines += tcpSteps.map(::formatTcpStepDsl)
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
                    tcpSteps += parseTcpStep(kind, parts[1], index + 1)
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
    return TcpChainStepModel(
        kind = kind,
        marker = normalizeTcpMarker(kind, marker),
        midhostMarker = normalizeMidhostMarker(kind, midhostMarker),
        fakeHostTemplate = normalizeFakeHostTemplate(kind, fakeHostTemplate),
    )
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
        .setMidhostMarker(normalizeMidhostMarker(kind, midhostMarker))
        .setFakeHostTemplate(normalizeFakeHostTemplate(kind, fakeHostTemplate))
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

private fun formatTcpStepSummary(step: TcpChainStepModel): String =
    buildString {
        append(step.kind.wireName)
        append('(')
        append(normalizeTcpMarker(step))
        val normalizedMidhost = normalizeMidhostMarker(step.kind, step.midhostMarker)
        if (normalizedMidhost.isNotEmpty()) {
            append(" midhost=")
            append(normalizedMidhost)
        }
        val normalizedTemplate = normalizeFakeHostTemplate(step.kind, step.fakeHostTemplate)
        if (normalizedTemplate.isNotEmpty()) {
            append(" host=")
            append(normalizedTemplate)
        }
        append(')')
    }

private fun formatTcpStepDsl(step: TcpChainStepModel): String =
    buildString {
        append(step.kind.wireName)
        append(' ')
        append(normalizeTcpMarker(step))
        val normalizedMidhost = normalizeMidhostMarker(step.kind, step.midhostMarker)
        if (normalizedMidhost.isNotEmpty()) {
            append(" midhost=")
            append(normalizedMidhost)
        }
        val normalizedTemplate = normalizeFakeHostTemplate(step.kind, step.fakeHostTemplate)
        if (normalizedTemplate.isNotEmpty()) {
            append(" host=")
            append(normalizedTemplate)
        }
    }

private fun parseTcpStep(
    kind: TcpChainStepKind,
    spec: String,
    lineNumber: Int,
): TcpChainStepModel {
    val tokens = spec.split(Regex("\\s+")).filter { it.isNotBlank() }
    require(tokens.isNotEmpty()) { "Missing marker on line $lineNumber" }
    val marker = normalizeTcpMarker(kind, tokens.first())
    require(isValidOffsetExpression(marker)) { "Invalid marker on line $lineNumber" }

    var midhostMarker = ""
    var fakeHostTemplate = ""
    tokens.drop(1).forEach { token ->
        val (key, value) = token.split('=', limit = 2).takeIf { it.size == 2 }
            ?: error("Invalid TCP step option '$token' on line $lineNumber")
        when (key.lowercase()) {
            "midhost" -> {
                require(kind == TcpChainStepKind.HostFake) { "midhost is only supported for hostfake on line $lineNumber" }
                val normalized = normalizeMidhostMarker(kind, value)
                require(normalized.isNotEmpty() && isValidOffsetExpression(normalized)) {
                    "Invalid midhost marker on line $lineNumber"
                }
                midhostMarker = normalized
            }

            "host" -> {
                require(kind == TcpChainStepKind.HostFake) { "host template is only supported for hostfake on line $lineNumber" }
                val normalized = normalizeFakeHostTemplate(kind, value)
                require(normalized.isNotEmpty()) { "Invalid host template on line $lineNumber" }
                fakeHostTemplate = normalized
            }

            else -> error("Unknown TCP step option '$key' on line $lineNumber")
        }
    }

    return TcpChainStepModel(
        kind = kind,
        marker = marker,
        midhostMarker = midhostMarker,
        fakeHostTemplate = fakeHostTemplate,
    )
}

private fun normalizeTcpMarker(
    kind: TcpChainStepKind,
    marker: String,
): String {
    val defaultValue = if (kind == TcpChainStepKind.TlsRec) DefaultTlsRecordMarker else DefaultSplitMarker
    return normalizeOffsetExpression(marker, defaultValue)
}

private fun normalizeMidhostMarker(
    kind: TcpChainStepKind,
    marker: String,
): String = if (kind == TcpChainStepKind.HostFake) normalizeOffsetExpression(marker, "").trim() else ""

private fun normalizeFakeHostTemplate(
    kind: TcpChainStepKind,
    template: String,
): String {
    if (kind != TcpChainStepKind.HostFake) {
        return ""
    }
    val trimmed = template.trim().trimEnd('.').lowercase()
    if (trimmed.isEmpty() || trimmed.contains(':') || trimmed.startsWith('.') || trimmed.endsWith('.') || trimmed.contains("..")) {
        return ""
    }
    if (!trimmed.all { it.isLowerCase() || it.isDigit() || it == '-' || it == '.' }) {
        return ""
    }
    if (trimmed.split('.').any { label -> label.isEmpty() || label.startsWith('-') || label.endsWith('-') }) {
        return ""
    }
    val ipv4Parts = trimmed.split('.')
    val isIpv4Literal =
        ipv4Parts.size == 4 &&
            ipv4Parts.all { part ->
                part.toIntOrNull()?.let { value -> value in 0..255 && value.toString() == part } == true
            }
    return if (isIpv4Literal) "" else trimmed
}
