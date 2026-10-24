package com.poyka.ripdpi.data.wireguard

/** Shared import, editor, and activation contract for the native AWG 3.1 subset. */
object AmneziaWgWireProfile {
    const val ID: String = "awg-3.1-ranges-v1"
    private const val MaximumHeader = 0xFFFF_FFFFL
    private const val MaximumPacket = 1280
    private const val MaximumProgramText = 4096
    private const val MaximumTags = 64
    private val Decimal = Regex("0|[1-9][0-9]*")
    private val Literal = Regex("[0-9a-f]+")

    fun headerRange(value: String): LongRange? {
        val parts = value.split('-')
        if (parts.size !in 1..2 || parts.any { !Decimal.matches(it) }) return null
        val low = parts[0].toLongOrNull()?.takeIf { it <= MaximumHeader } ?: return null
        val high = parts.last().toLongOrNull()?.takeIf { it <= MaximumHeader } ?: return null
        if (parts.size == 2 && low >= high) return null
        return low..high
    }

    fun headerIsValid(
        value: String,
        messageType: Int,
    ): Boolean =
        headerRange(value)?.let { range ->
            range.first > 4 || (range.first == messageType.toLong() && range.last == range.first)
        } == true

    fun signatureSize(program: String): Int? {
        if (program.length > MaximumProgramText) return null
        var offset = 0
        var size = 0
        var tags = 0
        while (offset < program.length) {
            if (program[offset] != '<' || tags == MaximumTags) return null
            val end = program.indexOf('>', offset + 1)
            if (end < 0) return null
            val tagSize = tagSize(program.substring(offset + 1, end)) ?: return null
            size += tagSize
            if (size > MaximumPacket) return null
            offset = end + 1
            tags++
        }
        return size
    }

    private fun tagSize(tag: String): Int? {
        if (tag == "t") return 4
        if (tag.startsWith("b 0x")) {
            val hex = tag.removePrefix("b 0x")
            return (hex.length / 2).takeIf { hex.length % 2 == 0 && Literal.matches(hex) }
        }
        val parts = tag.split(' ')
        if (parts.size != 2 || parts[0] !in setOf("r", "rd", "rc") || !Decimal.matches(parts[1])) return null
        return parts[1].toIntOrNull()?.takeIf { it in 1..MaximumPacket }
    }

    fun requireScalars(
        jc: Int?,
        jmin: Int?,
        jmax: Int?,
        s1: Int?,
        s2: Int?,
    ) {
        require((jc ?: 0) in 0..128) { "AmneziaWG Jc must be in 0..128" }
        require(listOf(jmin, jmax, s1, s2).all { (it ?: 0) in 0..MaximumPacket }) {
            "AmneziaWG packet sizes must be in 0..1280"
        }
        require((jmin ?: 0) <= (jmax ?: 0)) { "AmneziaWG junk size range is inverted" }
        require(148 + (s1 ?: 0) != 92 + (s2 ?: 0)) { "AmneziaWG handshake packet sizes must differ" }
    }

    fun requireValid(
        wireProfile: String,
        headers: List<String?>,
        signatures: List<String?>,
    ) {
        require(wireProfile == ID) { "Unsupported AmneziaWG wire profile" }
        require(headers.size == 4 && signatures.size == 5) { "Incomplete AmneziaWG field set" }
        val ranges =
            headers.mapIndexed { index, value ->
                val header = value ?: (index + 1).toString()
                require(headerIsValid(header, index + 1)) { "Invalid AmneziaWG H${index + 1}" }
                requireNotNull(headerRange(header))
            }
        ranges.forEachIndexed { index, range ->
            require(ranges.drop(index + 1).none { other -> range.first <= other.last && other.first <= range.last }) {
                "AmneziaWG header ranges overlap"
            }
        }
        signatures.forEachIndexed { index, program ->
            require(signatureSize(program.orEmpty()) != null) { "Invalid AmneziaWG I${index + 1}" }
        }
    }
}
