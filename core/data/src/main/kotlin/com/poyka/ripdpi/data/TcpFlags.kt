package com.poyka.ripdpi.data

private const val TcpFlagMaskAll = 0x0FFF
private const val HexRadix = 16

private data class TcpFlagDescriptor(
    val name: String,
    val mask: Int,
)

private val tcpFlagDescriptors =
    listOf(
        TcpFlagDescriptor(name = "fin", mask = 0x001),
        TcpFlagDescriptor(name = "syn", mask = 0x002),
        TcpFlagDescriptor(name = "rst", mask = 0x004),
        TcpFlagDescriptor(name = "psh", mask = 0x008),
        TcpFlagDescriptor(name = "ack", mask = 0x010),
        TcpFlagDescriptor(name = "urg", mask = 0x020),
        TcpFlagDescriptor(name = "ece", mask = 0x040),
        TcpFlagDescriptor(name = "cwr", mask = 0x080),
        TcpFlagDescriptor(name = "ae", mask = 0x100),
        TcpFlagDescriptor(name = "r1", mask = 0x200),
        TcpFlagDescriptor(name = "r2", mask = 0x400),
        TcpFlagDescriptor(name = "r3", mask = 0x800),
    )

val SupportedTcpFlagNames: List<String> = tcpFlagDescriptors.map(TcpFlagDescriptor::name)

private val tcpFlagMaskByName: Map<String, Int> = tcpFlagDescriptors.associate { it.name to it.mask }

val TcpChainStepKind.supportsFakeTcpFlags: Boolean
    get() =
        when (this) {
            TcpChainStepKind.SeqOverlap,
            TcpChainStepKind.Fake,
            TcpChainStepKind.FakeSplit,
            TcpChainStepKind.FakeDisorder,
            TcpChainStepKind.HostFake,
            TcpChainStepKind.FakeRst,
            -> true

            else -> false
        }

val TcpChainStepKind.supportsOriginalTcpFlags: Boolean
    get() =
        when (this) {
            TcpChainStepKind.Split,
            TcpChainStepKind.SynData,
            TcpChainStepKind.Disorder,
            TcpChainStepKind.MultiDisorder,
            TcpChainStepKind.Fake,
            TcpChainStepKind.FakeSplit,
            TcpChainStepKind.FakeDisorder,
            TcpChainStepKind.HostFake,
            TcpChainStepKind.IpFrag2,
            -> true

            else -> false
        }

fun parseTcpFlagMask(spec: String): Int {
    val trimmed = spec.trim()
    return when {
        trimmed.isEmpty() -> {
            0
        }

        else -> {
            parseNumericTcpFlagMask(trimmed)
                ?: trimmed
                    .split('|')
                    .map(String::trim)
                    .onEach { require(it.isNotEmpty()) { "TCP flag masks must not contain empty tokens" } }
                    .fold(0) { acc, token ->
                        acc or requireNotNull(tcpFlagMaskByName[token.lowercase()]) { "Unknown TCP flag '$token'" }
                    }
        }
    }
}

fun formatTcpFlagMask(mask: Int): String {
    require(mask in 0..TcpFlagMaskAll) { "TCP flag mask must be between 0 and $TcpFlagMaskAll" }
    if (mask == 0) {
        return ""
    }
    return tcpFlagDescriptors
        .filter { descriptor -> mask and descriptor.mask != 0 }
        .joinToString(separator = "|") { it.name }
}

fun normalizeTcpFlagMask(value: String): String =
    value
        .trim()
        .takeIf(String::isNotEmpty)
        ?.let { formatTcpFlagMask(parseTcpFlagMask(it)) }
        .orEmpty()

fun validateTcpFlagMasks(
    kind: TcpChainStepKind,
    tcpFlagsSet: String,
    tcpFlagsUnset: String,
    tcpFlagsOrigSet: String,
    tcpFlagsOrigUnset: String,
) {
    val fakeSet = parseMaskOrZero(tcpFlagsSet)
    val fakeUnset = parseMaskOrZero(tcpFlagsUnset)
    val origSet = parseMaskOrZero(tcpFlagsOrigSet)
    val origUnset = parseMaskOrZero(tcpFlagsOrigUnset)
    require(fakeSet and fakeUnset == 0) { "${kind.wireName} fake TCP flag set/unset masks must not overlap" }
    require(origSet and origUnset == 0) { "${kind.wireName} original TCP flag set/unset masks must not overlap" }
    require(kind.supportsFakeTcpFlags || (fakeSet == 0 && fakeUnset == 0)) {
        "${kind.wireName} must not declare fake TCP flag masks"
    }
    require(kind.supportsOriginalTcpFlags || (origSet == 0 && origUnset == 0)) {
        "${kind.wireName} must not declare original TCP flag masks"
    }
}

private fun parseMaskOrZero(value: String): Int = value.takeIf(String::isNotBlank)?.let(::parseTcpFlagMask) ?: 0

private fun parseNumericTcpFlagMask(value: String): Int? {
    val parsed =
        when {
            value.startsWith("0x", ignoreCase = true) -> value.substring(2).toIntOrNull(HexRadix)
            value.all(Char::isDigit) -> value.toIntOrNull()
            else -> null
        } ?: return null
    require(parsed in 0..TcpFlagMaskAll) { "TCP flag mask must be between 0 and $TcpFlagMaskAll" }
    return parsed
}
