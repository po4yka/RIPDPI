package com.poyka.ripdpi.data.wireguard

/** Shared import, editor, and activation contract for the native AWG 3.1 subset. */
object AmneziaWgWireProfile {
    const val ID: String = "awg-3.1-ranges-v1"
    private const val MaximumHeader = 0xFFFF_FFFFL
    const val MaximumPacket = 1280
    const val MaximumJunkPackets = 128
    const val InitiationType = 1
    const val ResponseType = 2
    const val CookieType = 3
    const val TransportType = 4
    private const val InitiationSize = 148
    private const val ResponseSize = 92
    private const val HeaderCount = 4
    private const val SignatureSlots = 5
    private const val TimestampBytes = 4
    private const val MaximumProgramText = 4096
    private const val MaximumTags = 64
    private val Decimal = Regex("0|[1-9][0-9]*")
    private val Literal = Regex("[0-9a-f]+")

    fun headerRange(value: String): LongRange? =
        value.split('-').takeIf { parts -> parts.size in 1..2 && parts.all { Decimal.matches(it) } }?.let { parts ->
            val low = parts[0].toLongOrNull()?.takeIf { it <= MaximumHeader }
            val high = parts.last().toLongOrNull()?.takeIf { it <= MaximumHeader }
            // amneziawg-go accepts `x-x`; only `hi < lo` is invalid.
            if (low == null || high == null) null else (low..high).takeIf { low <= high }
        }

    fun headerIsValid(
        value: String,
        messageType: Int,
    ): Boolean =
        headerRange(value)?.let { range ->
            range.first > TransportType || (range.first == messageType.toLong() && range.last == range.first)
        } == true

    fun signatureSize(program: String): Int? {
        if (program.length > MaximumProgramText) return null
        var offset = 0
        var size: Int? = 0
        var tags = 0
        while (offset < program.length && size != null) {
            val end = program.indexOf('>', offset + 1)
            val nextSize =
                if (program[offset] == '<' && tags < MaximumTags && end >= 0) {
                    tagSize(program.substring(offset + 1, end))
                } else {
                    null
                }
            size = nextSize?.plus(size)?.takeIf { it <= MaximumPacket }
            offset = end + 1
            tags++
        }
        return size
    }

    private fun tagSize(tag: String): Int? =
        when {
            tag == "t" -> {
                TimestampBytes
            }

            tag.startsWith("b 0x") -> {
                val hex = tag.removePrefix("b 0x")
                (hex.length / 2).takeIf { hex.length % 2 == 0 && Literal.matches(hex) }
            }

            else -> {
                randomTagSize(tag)
            }
        }

    private fun randomTagSize(tag: String): Int? {
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
        require((jc ?: 0) in 0..MaximumJunkPackets) { "AmneziaWG Jc must be in 0..128" }
        require(listOf(jmin, jmax, s1, s2).all { (it ?: 0) in 0..MaximumPacket }) {
            "AmneziaWG packet sizes must be in 0..1280"
        }
        require((jmin ?: 0) <= (jmax ?: 0)) { "AmneziaWG junk size range is inverted" }
        require(
            InitiationSize + (s1 ?: 0) != ResponseSize + (s2 ?: 0),
        ) { "AmneziaWG handshake packet sizes must differ" }
    }

    fun requireValid(
        wireProfile: String,
        headers: List<String?>,
        signatures: List<String?>,
    ) {
        require(wireProfile == ID) { "Unsupported AmneziaWG wire profile" }
        require(headers.size == HeaderCount && signatures.size == SignatureSlots) { "Incomplete AmneziaWG field set" }
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
