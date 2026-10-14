package com.poyka.ripdpi.data

private const val Sha256HexLength = 64

/**
 * Normalizes a Hysteria2 certificate pin to 64 lowercase hex characters.
 *
 * The pin is the SHA-256 digest of the server's leaf certificate in DER form.
 * This matches the upstream Hysteria2 `pinSHA256` option and the Clash/mihomo
 * `fingerprint` option. The function accepts the forms that upstream accepts:
 * plain hex or hex separated by `:` or `-`, in any letter case.
 *
 * Returns `null` when [raw] is not a valid SHA-256 digest. An importer must
 * then reject the node: if it drops the pin and keeps `insecure`, the profile
 * accepts any certificate.
 */
fun normalizeHysteria2PinSha256(raw: String): String? {
    val hex =
        raw
            .trim()
            .replace(":", "")
            .replace("-", "")
            .lowercase()
    val isSha256Hex = hex.length == Sha256HexLength && hex.all { it in '0'..'9' || it in 'a'..'f' }
    return hex.takeIf { isSha256Hex }
}
