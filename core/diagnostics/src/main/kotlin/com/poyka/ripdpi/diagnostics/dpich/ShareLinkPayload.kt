package com.poyka.ripdpi.diagnostics.dpich

private const val ByteMax = 0xff
private const val U24Max = 0xff_ffff
private const val MaxShareLinkItems = 255

enum class AliveState {
    NO,
    YES,
    UNKNOWN,
}

enum class DpiState {
    NOT_DETECTED,
    DETECTED,
    PROBABLY,
    POSSIBLE,
    UNLIKELY,
}

data class ShareLinkItem(
    val alive: AliveState,
    val dpi: DpiState,
)

data class ShareLinkPayload(
    val commitHash: Int,
    val timestampMinutes: Int,
    val asn: Int,
    val items: List<ShareLinkItem>,
) {
    init {
        require(commitHash in 0..ByteMax) { "commitHash must fit in 8 bits" }
        require(timestampMinutes in 0..U24Max) { "timestampMinutes must fit in 24 bits" }
        require(asn in 0..U24Max) { "asn must fit in 24 bits" }
        require(items.size <= MaxShareLinkItems) { "items must fit in an 8-bit count" }
    }
}
