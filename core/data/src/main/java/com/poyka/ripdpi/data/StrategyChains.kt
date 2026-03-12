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
    TlsRandRec("tlsrandrec"),
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
                TlsRandRec -> null
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

val TcpChainStepKind.supportsAdaptiveMarker: Boolean
    get() =
        when (this) {
            TcpChainStepKind.HostFake -> false
            else -> true
        }

@Serializable
data class TcpChainStepModel(
    val kind: TcpChainStepKind,
    val marker: String,
    val midhostMarker: String = "",
    val fakeHostTemplate: String = "",
    val fragmentCount: Int = 0,
    val minFragmentSize: Int = 0,
    val maxFragmentSize: Int = 0,
    val activationFilter: ActivationFilterModel = ActivationFilterModel(),
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
    val activationFilter: ActivationFilterModel = ActivationFilterModel(),
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
            formatUdpStepSummary(it)
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
        lines += udpSteps.map(::formatUdpStepDsl)
    }
    return lines.joinToString("\n")
}

fun primaryTcpChainStep(tcpSteps: List<TcpChainStepModel>): TcpChainStepModel? =
    tcpSteps.firstOrNull { !it.kind.isTlsPrelude }

fun rewritePrimaryTcpMarker(
    tcpSteps: List<TcpChainStepModel>,
    marker: String,
): List<TcpChainStepModel> {
    val index = tcpSteps.indexOfFirst { !it.kind.isTlsPrelude }
    if (index < 0) {
        return tcpSteps
    }
    val step = tcpSteps[index]
    if (!step.kind.supportsAdaptiveMarker) {
        return tcpSteps
    }
    return tcpSteps.toMutableList().apply {
        this[index] = normalizeTcpChainStepModel(step.copy(marker = marker))
    }
}

fun tlsPreludeTcpChainStep(tcpSteps: List<TcpChainStepModel>): TcpChainStepModel? =
    tcpSteps.firstOrNull { it.kind.isTlsPrelude }

fun tlsPreludeTcpChainSteps(tcpSteps: List<TcpChainStepModel>): List<TcpChainStepModel> =
    tcpSteps.filter { it.kind.isTlsPrelude }

fun replaceTlsPreludeTcpChainSteps(
    tcpSteps: List<TcpChainStepModel>,
    newPreludeSteps: List<TcpChainStepModel>,
): List<TcpChainStepModel> {
    val updated =
        newPreludeSteps.map(::normalizeTcpChainStepModel) +
            tcpSteps.filterNot { it.kind.isTlsPrelude }
    validateTcpChain(updated)
    return updated
}

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
                    val tokens = parts[1].split(Regex("\\s+")).filter { it.isNotBlank() }
                    val count = tokens.firstOrNull()?.toIntOrNull() ?: error("Invalid UDP count on line ${index + 1}")
                    require(count >= 0) { "Invalid UDP count on line ${index + 1}" }
                    var activationFilter = ActivationFilterModel()
                    tokens.drop(1).forEach { token ->
                        val (key, value) = token.split('=', limit = 2).takeIf { it.size == 2 }
                            ?: error("Invalid UDP step option '$token' on line ${index + 1}")
                        activationFilter =
                            parseActivationToken(
                                activationFilter = activationFilter,
                                key = key,
                                value = value,
                                lineNumber = index + 1,
                            )
                    }
                    udpSteps += UdpChainStepModel(kind = kind, count = count, activationFilter = activationFilter)
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
        val primaryTcpStep = primaryTcpChainStep(tcpSteps)
        val tlsRecStep = tlsPreludeTcpChainStep(tcpSteps)

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
    return normalizeTcpChainStepModel(
        TcpChainStepModel(
            kind = kind,
            marker = normalizeTcpMarker(kind, marker),
            midhostMarker = normalizeMidhostMarker(kind, midhostMarker),
            fakeHostTemplate = normalizeFakeHostTemplate(kind, fakeHostTemplate),
            fragmentCount = fragmentCount,
            minFragmentSize = minFragmentSize,
            maxFragmentSize = maxFragmentSize,
            activationFilter = if (hasActivationFilter()) activationFilter.toModel() else ActivationFilterModel(),
        ),
    )
}

private fun StrategyUdpStep.toModelOrNull(): UdpChainStepModel? {
    val resolvedKind = UdpChainStepKind.fromWireName(kind) ?: return null
    return UdpChainStepModel(
        kind = resolvedKind,
        count = count,
        activationFilter = if (hasActivationFilter()) activationFilter.toModel() else ActivationFilterModel(),
    )
}

private fun TcpChainStepModel.toProto(): StrategyTcpStep =
    normalizeTcpChainStepModel(this).let { step ->
        StrategyTcpStep
            .newBuilder()
            .setKind(step.kind.wireName)
            .setMarker(normalizeTcpMarker(step))
            .setMidhostMarker(normalizeMidhostMarker(step.kind, step.midhostMarker))
            .setFakeHostTemplate(normalizeFakeHostTemplate(step.kind, step.fakeHostTemplate))
            .setFragmentCount(normalizeFragmentCount(step.kind, step.fragmentCount))
            .setMinFragmentSize(normalizeMinFragmentSize(step.kind, step.minFragmentSize))
            .setMaxFragmentSize(normalizeMaxFragmentSize(step.kind, step.maxFragmentSize))
            .apply {
                if (!step.activationFilter.isEmpty) {
                    setActivationFilter(step.activationFilter.toProto())
                }
            }.build()
    }

private fun UdpChainStepModel.toProto(): StrategyUdpStep =
    StrategyUdpStep
        .newBuilder()
        .setKind(kind.wireName)
        .setCount(count.coerceAtLeast(0))
        .apply {
            if (!this@toProto.activationFilter.isEmpty) {
                setActivationFilter(this@toProto.activationFilter.toProto())
            }
        }.build()

private fun validateTcpChain(steps: List<TcpChainStepModel>) {
    var sawSendStep = false
    steps.forEach { step ->
        when (step.kind) {
            TcpChainStepKind.TlsRec,
            TcpChainStepKind.TlsRandRec,
            -> require(!sawSendStep) { "${step.kind.wireName} must be declared before tcp send steps" }
            else -> sawSendStep = true
        }
        validateTcpStepOptions(step)
    }
}

private fun normalizeTcpMarker(step: TcpChainStepModel): String = normalizeTcpMarker(step.kind, step.marker)

private fun formatTcpStepSummary(step: TcpChainStepModel): String =
    buildString {
        val normalized = normalizeTcpChainStepModel(step)
        append(normalized.kind.wireName)
        append('(')
        append(formatOffsetExpressionLabel(normalizeTcpMarker(normalized)))
        val normalizedMidhost = normalizeMidhostMarker(normalized.kind, normalized.midhostMarker)
        if (normalizedMidhost.isNotEmpty()) {
            append(" midhost=")
            append(normalizedMidhost)
        }
        val normalizedTemplate = normalizeFakeHostTemplate(normalized.kind, normalized.fakeHostTemplate)
        if (normalizedTemplate.isNotEmpty()) {
            append(" host=")
            append(normalizedTemplate)
        }
        if (normalized.kind == TcpChainStepKind.TlsRandRec) {
            append(" count=")
            append(normalized.fragmentCount)
            append(" min=")
            append(normalized.minFragmentSize)
            append(" max=")
            append(normalized.maxFragmentSize)
        }
        val filterSummary = formatActivationFilterSummary(normalized.activationFilter)
        if (filterSummary.isNotBlank()) {
            append(' ')
            append(filterSummary)
        }
        append(')')
    }

private fun formatTcpStepDsl(step: TcpChainStepModel): String =
    buildString {
        val normalized = normalizeTcpChainStepModel(step)
        append(normalized.kind.wireName)
        append(' ')
        append(normalizeTcpMarker(normalized))
        val normalizedMidhost = normalizeMidhostMarker(normalized.kind, normalized.midhostMarker)
        if (normalizedMidhost.isNotEmpty()) {
            append(" midhost=")
            append(normalizedMidhost)
        }
        val normalizedTemplate = normalizeFakeHostTemplate(normalized.kind, normalized.fakeHostTemplate)
        if (normalizedTemplate.isNotEmpty()) {
            append(" host=")
            append(normalizedTemplate)
        }
        if (normalized.kind == TcpChainStepKind.TlsRandRec) {
            append(" count=")
            append(normalized.fragmentCount)
            append(" min=")
            append(normalized.minFragmentSize)
            append(" max=")
            append(normalized.maxFragmentSize)
        }
        appendActivationDsl(this, normalized.activationFilter)
    }

private fun formatUdpStepSummary(step: UdpChainStepModel): String =
    buildString {
        append(step.kind.wireName)
        append('(')
        append(step.count.coerceAtLeast(0))
        val filterSummary = formatActivationFilterSummary(step.activationFilter)
        if (filterSummary.isNotBlank()) {
            append(' ')
            append(filterSummary)
        }
        append(')')
    }

private fun formatUdpStepDsl(step: UdpChainStepModel): String =
    buildString {
        append(step.kind.wireName)
        append(' ')
        append(step.count.coerceAtLeast(0))
        appendActivationDsl(this, step.activationFilter)
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
    require(kind.supportsAdaptiveMarker || !isAdaptiveOffsetExpression(marker)) {
        "Adaptive markers are not supported for ${kind.wireName} on line $lineNumber"
    }

    var midhostMarker = ""
    var fakeHostTemplate = ""
    var fragmentCount = 0
    var minFragmentSize = 0
    var maxFragmentSize = 0
    var activationFilter = ActivationFilterModel()
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
                require(!isAdaptiveOffsetExpression(normalized)) {
                    "Adaptive markers are not supported for hostfake midhost on line $lineNumber"
                }
                midhostMarker = normalized
            }

            "host" -> {
                require(kind == TcpChainStepKind.HostFake) { "host template is only supported for hostfake on line $lineNumber" }
                val normalized = normalizeFakeHostTemplate(kind, value)
                require(normalized.isNotEmpty()) { "Invalid host template on line $lineNumber" }
                fakeHostTemplate = normalized
            }

            "count" -> {
                require(kind == TcpChainStepKind.TlsRandRec) { "count is only supported for tlsrandrec on line $lineNumber" }
                fragmentCount = value.toIntOrNull() ?: error("Invalid count on line $lineNumber")
            }

            "min" -> {
                require(kind == TcpChainStepKind.TlsRandRec) { "min is only supported for tlsrandrec on line $lineNumber" }
                minFragmentSize = value.toIntOrNull() ?: error("Invalid min on line $lineNumber")
            }

            "max" -> {
                require(kind == TcpChainStepKind.TlsRandRec) { "max is only supported for tlsrandrec on line $lineNumber" }
                maxFragmentSize = value.toIntOrNull() ?: error("Invalid max on line $lineNumber")
            }

            "when_round",
            "when_size",
            "when_stream",
            ->
                activationFilter =
                    parseActivationToken(
                        activationFilter = activationFilter,
                        key = key,
                        value = value,
                        lineNumber = lineNumber,
                    )

            else -> error("Unknown TCP step option '$key' on line $lineNumber")
        }
    }

    if (kind == TcpChainStepKind.TlsRandRec) {
        require(fragmentCount > 0) { "Missing count on line $lineNumber" }
        require(minFragmentSize > 0) { "Missing min on line $lineNumber" }
        require(maxFragmentSize > 0) { "Missing max on line $lineNumber" }
    }

    return normalizeTcpChainStepModel(
        TcpChainStepModel(
            kind = kind,
            marker = marker,
            midhostMarker = midhostMarker,
            fakeHostTemplate = fakeHostTemplate,
            fragmentCount = fragmentCount,
            minFragmentSize = minFragmentSize,
            maxFragmentSize = maxFragmentSize,
            activationFilter = activationFilter,
        ),
    )
}

private fun normalizeTcpMarker(
    kind: TcpChainStepKind,
    marker: String,
): String {
    val defaultValue = if (kind.isTlsPrelude) DefaultTlsRecordMarker else DefaultSplitMarker
    return normalizeOffsetExpression(marker, defaultValue)
}

private fun validateTcpStepOptions(step: TcpChainStepModel) {
    require(step.kind.supportsAdaptiveMarker || !isAdaptiveOffsetExpression(step.marker)) {
        "${step.kind.wireName} must not declare an adaptive marker"
    }
    require(step.kind != TcpChainStepKind.HostFake || !isAdaptiveOffsetExpression(step.midhostMarker)) {
        "hostfake must not declare an adaptive midhost marker"
    }
    when (step.kind) {
        TcpChainStepKind.TlsRandRec -> {
            require(step.fragmentCount in 2..16) { "tlsrandrec count must be between 2 and 16" }
            require(step.minFragmentSize in 1..4096) { "tlsrandrec min must be between 1 and 4096" }
            require(step.maxFragmentSize in step.minFragmentSize..4096) {
                "tlsrandrec max must be between min and 4096"
            }
        }

        else -> {
            require(step.fragmentCount == 0) { "${step.kind.wireName} must not declare fragmentCount" }
            require(step.minFragmentSize == 0) { "${step.kind.wireName} must not declare minFragmentSize" }
            require(step.maxFragmentSize == 0) { "${step.kind.wireName} must not declare maxFragmentSize" }
        }
    }
}

private fun normalizeFragmentCount(
    kind: TcpChainStepKind,
    value: Int,
): Int = if (kind == TcpChainStepKind.TlsRandRec) value.takeIf { it > 0 } ?: DefaultTlsRandRecFragmentCount else 0

private fun normalizeMinFragmentSize(
    kind: TcpChainStepKind,
    value: Int,
): Int = if (kind == TcpChainStepKind.TlsRandRec) value.takeIf { it > 0 } ?: DefaultTlsRandRecMinFragmentSize else 0

private fun normalizeMaxFragmentSize(
    kind: TcpChainStepKind,
    value: Int,
): Int = if (kind == TcpChainStepKind.TlsRandRec) value.takeIf { it > 0 } ?: DefaultTlsRandRecMaxFragmentSize else 0

fun normalizeTcpChainStepModel(step: TcpChainStepModel): TcpChainStepModel =
    step.copy(
        marker = normalizeTcpMarker(step.kind, step.marker),
        midhostMarker = normalizeMidhostMarker(step.kind, step.midhostMarker),
        fakeHostTemplate = normalizeFakeHostTemplate(step.kind, step.fakeHostTemplate),
        fragmentCount = normalizeFragmentCount(step.kind, step.fragmentCount),
        minFragmentSize = normalizeMinFragmentSize(step.kind, step.minFragmentSize),
        maxFragmentSize = normalizeMaxFragmentSize(step.kind, step.maxFragmentSize),
        activationFilter = normalizeActivationFilter(step.activationFilter),
    )

val TcpChainStepKind.isTlsPrelude: Boolean
    get() = this == TcpChainStepKind.TlsRec || this == TcpChainStepKind.TlsRandRec

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

private fun appendActivationDsl(
    builder: StringBuilder,
    activationFilter: ActivationFilterModel,
) {
    formatNumericRange(activationFilter.round)?.let {
        builder.append(" when_round=")
        builder.append(it)
    }
    formatNumericRange(activationFilter.payloadSize)?.let {
        builder.append(" when_size=")
        builder.append(it)
    }
    formatNumericRange(activationFilter.streamBytes)?.let {
        builder.append(" when_stream=")
        builder.append(it)
    }
}

private fun parseActivationToken(
    activationFilter: ActivationFilterModel,
    key: String,
    value: String,
    lineNumber: Int,
): ActivationFilterModel =
    when (key.lowercase()) {
        "when_round" ->
            activationFilter.copy(
                round =
                    runCatching { parseRoundRange(value) }.getOrElse {
                        error("Invalid round filter on line $lineNumber")
                    },
            )

        "when_size" ->
            activationFilter.copy(
                payloadSize =
                    runCatching { parsePayloadSizeRange(value) }.getOrElse {
                        error("Invalid payload size filter on line $lineNumber")
                    },
            )

        "when_stream" ->
            activationFilter.copy(
                streamBytes =
                    runCatching { parseStreamBytesRange(value) }.getOrElse {
                        error("Invalid stream byte filter on line $lineNumber")
                    },
            )

        else -> error("Unknown activation filter '$key' on line $lineNumber")
    }
