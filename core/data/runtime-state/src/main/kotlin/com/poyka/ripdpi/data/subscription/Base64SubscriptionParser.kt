package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import java.util.Base64

/**
 * A line that could not be parsed into a [ProxyProfile] by [ProxyUriCodec].
 * Carries enough context to surface a non-fatal, typed warning to the user.
 */
data class SubscriptionLineWarning(
    val lineNumber: Int,
    val line: String,
    val reason: String,
)

/** Outcome of a [Base64SubscriptionParser] run: parsed profiles plus per-line warnings. */
data class Base64SubscriptionResult(
    val profiles: List<ProxyProfile>,
    val warnings: List<SubscriptionLineWarning>,
)

/**
 * Fallback subscription parser for payloads that are either URL-safe
 * base64-encoded newline-delimited proxy URIs, or already-decoded plain URI
 * lists.
 *
 * Detection mirrors NekoBox's `RawUpdater.parseProxies()`: a URL-safe base64
 * decode is attempted first and, on failure, the raw text is used as-is. The
 * decoded text is then split line-by-line; blank lines and `#` comment lines
 * are skipped; each remaining line is handed to the shared [ProxyUriCodec].
 * Unknown schemes produce a [SubscriptionLineWarning] rather than aborting.
 */
object Base64SubscriptionParser {
    /** Base64 encodes 3 input bytes per 4 output chars, so encoded length is always a multiple of 4. */
    private const val BASE64_GROUP_SIZE = 4

    /**
     * Parses [payload] into a [Base64SubscriptionResult]. Every produced
     * [ProxyProfile] is stamped with [groupId]. Streaming, line-by-line; never
     * throws.
     */
    fun parse(
        payload: String,
        groupId: String,
    ): Base64SubscriptionResult {
        val text = decodeBase64OrPlain(payload)
        val profiles = mutableListOf<ProxyProfile>()
        val warnings = mutableListOf<SubscriptionLineWarning>()

        var lineNumber = 0
        text.lineSequence().forEach { rawLine ->
            lineNumber += 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val profile = ProxyUriCodec.parse(line)
            if (profile != null) {
                profiles += withGroupId(profile, groupId)
            } else {
                warnings +=
                    SubscriptionLineWarning(
                        lineNumber = lineNumber,
                        line = line,
                        reason = "unrecognised or malformed proxy URI",
                    )
            }
        }
        return Base64SubscriptionResult(profiles = profiles, warnings = warnings)
    }

    /**
     * Attempts a URL-safe base64 decode of the whole payload; on any failure —
     * or when the decoded bytes do not look like text — falls back to the raw
     * payload. Splits on `\r\n`, `\r`, and `\n` are handled downstream by
     * [lineSequence].
     */
    @Suppress("ReturnCount")
    private fun decodeBase64OrPlain(payload: String): String {
        val condensed =
            payload
                .trim()
                .replace("\n", "")
                .replace("\r", "")
                .replace(" ", "")
        if (condensed.isEmpty()) return payload
        val padded = padBase64(condensed)
        val decoders = listOf(Base64.getUrlDecoder(), Base64.getMimeDecoder(), Base64.getDecoder())
        for (decoder in decoders) {
            val decoded = runCatching { decoder.decode(padded) }.getOrNull() ?: continue
            val asText = String(decoded)
            if (looksLikeUriList(asText)) return asText.replace("\r\n", "\n").replace('\r', '\n')
        }
        return payload.replace("\r\n", "\n").replace('\r', '\n')
    }

    /** A decoded blob is a URI list when at least one line opens a known scheme. */
    private fun looksLikeUriList(text: String): Boolean =
        text.lineSequence().any { line ->
            val trimmed = line.trim()
            trimmed.contains("://") && !trimmed.startsWith("#")
        }

    private fun padBase64(value: String): String {
        val normalized = value.replace('-', '+').replace('_', '/')
        val remainder = normalized.length % BASE64_GROUP_SIZE
        return if (remainder == 0) {
            normalized
        } else {
            normalized + "=".repeat(BASE64_GROUP_SIZE - remainder)
        }
    }

    private fun withGroupId(
        profile: ProxyProfile,
        groupId: String,
    ): ProxyProfile =
        when (profile) {
            is ProxyProfile.Vless -> profile.copy(groupId = groupId)
            is ProxyProfile.VlessReality -> profile.copy(groupId = groupId)
            is ProxyProfile.Shadowsocks -> profile.copy(groupId = groupId)
            is ProxyProfile.Trojan -> profile.copy(groupId = groupId)
            is ProxyProfile.Hysteria2 -> profile.copy(groupId = groupId)
            is ProxyProfile.AnyTls -> profile.copy(groupId = groupId)
            is ProxyProfile.TrojanGo -> profile.copy(groupId = groupId)
            is ProxyProfile.Mieru -> profile.copy(groupId = groupId)
            is ProxyProfile.HysteriaV1 -> profile.copy(groupId = groupId)
            is ProxyProfile.Vmess -> profile.copy(groupId = groupId)
            is ProxyProfile.RawConfig -> profile.copy(groupId = groupId)
        }
}
