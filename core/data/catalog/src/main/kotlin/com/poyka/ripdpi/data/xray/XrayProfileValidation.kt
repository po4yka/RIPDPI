package com.poyka.ripdpi.data.xray

import java.util.Base64

/** Static checks for the supported client shape, shared by import and runtime rendering. */
internal object XrayProfileValidation {
    fun error(profile: XrayProfile): String? {
        val outbound = profile.outbound
        return when {
            outbound.serverAddress.isBlank() || outbound.serverAddress.any { it.isWhitespace() || it in "/@?#\\" } -> {
                "Provider requires a valid server address."
            }

            outbound.serverPort !in 1..MaxPort -> {
                "Provider requires a valid server port."
            }

            !validIdentity(outbound.uuid) -> {
                "Provider requires a valid VLESS identity."
            }

            outbound.security == XrayProfile.Security.REALITY && !validReality(outbound.reality) -> {
                "Provider requires valid REALITY client settings."
            }

            outbound.security == XrayProfile.Security.TLS && !validFingerprint(outbound.tls?.fingerprint.orEmpty()) -> {
                "Provider uses an unsupported TLS fingerprint."
            }

            outbound.network == XrayProfile.Network.XHTTP && outbound.xhttp?.mode.orEmpty() !in XhttpModes -> {
                "Provider uses an unsupported XHTTP mode."
            }

            else -> {
                null
            }
        }
    }

    // Xray v26.3.27 accepts a UUID or a nonempty UTF-8 identifier up to 30 bytes.
    private fun validIdentity(value: String): Boolean =
        value.isNotBlank() && (value.toByteArray(Charsets.UTF_8).size in 1..MaxIdentityBytes || Uuid.matches(value))

    private fun validReality(reality: XrayProfile.Reality?): Boolean =
        reality != null && validPublicKey(reality.publicKey) && ShortId.matches(reality.shortId) &&
            validFingerprint(reality.fingerprint) && reality.fingerprint.lowercase() != "hellogolang"

    private fun validPublicKey(value: String): Boolean =
        RawKey.matches(value) &&
            runCatching { Base64.getUrlDecoder().decode(value).size == KeyBytes }.getOrDefault(false)

    private fun validFingerprint(value: String): Boolean = value.lowercase() in Fingerprints

    private const val MaxPort = 65_535
    private const val MaxIdentityBytes = 30
    private const val KeyBytes = 32
    private val Uuid = Regex("(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})")
    private val RawKey = Regex("[A-Za-z0-9_-]{43}")
    private val ShortId = Regex("(?:[0-9a-fA-F]{2}){0,8}")
    private val XhttpModes = setOf("", "auto", "packet-up", "stream-up", "stream-one")

    // Pinned engine registry: XTLS/Xray-core v26.3.27 transport/internet/tls/tls.go.
    private val Fingerprints =
        setOf(
            "",
            "chrome",
            "firefox",
            "safari",
            "ios",
            "android",
            "edge",
            "360",
            "qq",
            "random",
            "randomized",
            "randomizednoalpn",
            "hellogolang",
            "hellorandomized",
            "hellorandomizedalpn",
            "hellorandomizednoalpn",
            "hellofirefox_auto",
            "hellofirefox_55",
            "hellofirefox_56",
            "hellofirefox_63",
            "hellofirefox_65",
            "hellofirefox_99",
            "hellofirefox_102",
            "hellofirefox_105",
            "hellofirefox_120",
            "hellochrome_auto",
            "hellochrome_58",
            "hellochrome_62",
            "hellochrome_70",
            "hellochrome_72",
            "hellochrome_83",
            "hellochrome_87",
            "hellochrome_96",
            "hellochrome_100",
            "hellochrome_102",
            "hellochrome_106_shuffle",
            "hellochrome_120",
            "hellochrome_131",
            "helloios_auto",
            "helloios_11_1",
            "helloios_12_1",
            "helloios_13",
            "helloios_14",
            "helloandroid_11_okhttp",
            "helloedge_auto",
            "helloedge_85",
            "helloedge_106",
            "hellosafari_auto",
            "hellosafari_16_0",
            "hello360_auto",
            "hello360_7_5",
            "hello360_11_0",
            "helloqq_auto",
            "helloqq_11_1",
            "hellochrome_100_psk",
            "hellochrome_112_psk_shuf",
            "hellochrome_114_padding_psk_shuf",
            "hellochrome_115_pq",
            "hellochrome_115_pq_psk",
            "hellochrome_120_pq",
        )
}
