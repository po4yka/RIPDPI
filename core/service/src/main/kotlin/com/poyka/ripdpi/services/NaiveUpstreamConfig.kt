package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val NaiveUpstreamConfigDirName = "naive"
private const val OwnerOnlyFilePermissions = "rw-------"
private const val OwnerOnlyDirPermissions = "rwx------"
private const val UnreservedUserInfoChars = "-._~"
private const val HexRadix = 16
private const val ByteMask = 0xFF
private const val Ipv4OctetCount = 4
private const val MaxIpv4OctetDigits = 3

/** The values of one upstream launch, already resolved; none of them may reach argv. */
internal data class NaiveUpstreamConfigValues(
    val proxyHost: String,
    val proxyPort: Int,
    val proxyUsername: String,
    val proxyPassword: String,
    val localUsername: String,
    val localPassword: String,
    val localHost: String,
    val localPort: Int,
    val extraHeaders: String,
    val mappedAddress: String?,
    val quic: Boolean = false,
    val concurrency: Int = 1,
)

/** Percent-encodes everything except RFC 3986 unreserved characters, so `@`, `:` and `+` cannot break the URI. */
internal fun percentEncodeUserInfo(
    value: String,
    lowercaseHex: Boolean = false,
): String =
    buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and ByteMask
            val char = code.toChar()
            if (isUnreservedAscii(char)) {
                append(char)
            } else {
                val hex = code.toString(HexRadix).padStart(2, '0')
                append('%').append(if (lowercaseHex) hex else hex.uppercase())
            }
        }
    }

private fun isUnreservedAscii(char: Char): Boolean =
    char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in UnreservedUserInfoChars

/** True for an IPv4 dotted quad or an IPv6 literal, bracketed or not. Never performs a lookup. */
internal fun isIpLiteralHost(host: String): Boolean {
    val bare = host.removePrefix("[").removeSuffix("]")
    if (bare.contains(':')) {
        return bare.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' || it == '%' }
    }
    val octets = bare.split('.')
    return octets.size == Ipv4OctetCount &&
        octets.all { part -> part.length in 1..MaxIpv4OctetDigits && part.all(Char::isDigit) }
}

internal fun bracketIpv6Host(host: String): String =
    if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

private fun userInfo(
    username: String,
    password: String,
): String =
    when {
        username.isEmpty() && password.isEmpty() -> ""
        else -> "${percentEncodeUserInfo(username)}:${percentEncodeUserInfo(password)}@"
    }

/** HTTP/3 and the connection count, which only the upstream client supports. */
internal fun NaiveUpstreamConfigValues.withProfileOptions(
    config: ResolvedRipDpiRelayConfig,
): NaiveUpstreamConfigValues = copy(quic = config.naiveQuic, concurrency = config.naiveConcurrency)

/** Keys mirror the upstream flags without the leading `--` (USAGE.txt of the pinned release). */
internal fun naiveUpstreamConfigJson(values: NaiveUpstreamConfigValues): String {
    val json = JSONObject()
    val localUserInfo = userInfo(values.localUsername, values.localPassword)
    json.put("listen", "socks://$localUserInfo${bracketIpv6Host(values.localHost)}:${values.localPort}")
    json.put(
        "proxy",
        "${if (values.quic) "quic" else "https"}://${userInfo(values.proxyUsername, values.proxyPassword)}" +
            "${bracketIpv6Host(values.proxyHost)}:${values.proxyPort}",
    )
    if (values.extraHeaders.isNotEmpty()) {
        json.put("extra-headers", values.extraHeaders)
    }
    if (values.concurrency > 1) {
        json.put("insecure-concurrency", values.concurrency)
    }
    values.mappedAddress?.let { address ->
        json.put("host-resolver-rules", "MAP ${values.proxyHost} ${bracketIpv6Host(address)}")
    }
    // An empty path means stderr, which the existing output capture reads.
    json.put("log", "")
    return json.toString()
}

/** Strings that must never appear in logs: raw and percent-encoded forms of every credential. */
internal fun naiveUpstreamRedactions(values: NaiveUpstreamConfigValues): List<String> =
    listOf(
        values.proxyUsername,
        values.proxyPassword,
        values.localUsername,
        values.localPassword,
    ).flatMap { listOf(it, percentEncodeUserInfo(it), percentEncodeUserInfo(it, lowercaseHex = true)) }
        .plus(basicCredentialForms(values.proxyUsername, values.proxyPassword))
        .plus(basicCredentialForms(values.localUsername, values.localPassword))
        .filter(String::isNotBlank)
        .distinct()

/** The `user:password` pair as a Basic Proxy-Authorization header carries it: padded and unpadded base64. */
private fun basicCredentialForms(
    username: String,
    password: String,
): List<String> {
    if (username.isEmpty() && password.isEmpty()) return emptyList()
    val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    return listOf(encoded, encoded.trimEnd('='))
}

/**
 * Owner-only config files under `<noBackupFilesDir>/naive`. The upstream client reads the file at
 * startup only, so the caller deletes it right after readiness, on every failure and on stop.
 */
internal class NaiveUpstreamConfigStore(
    private val baseDirectory: () -> File,
) {
    private val staleSwept = AtomicBoolean(false)
    private val directory: File get() = File(baseDirectory(), NaiveUpstreamConfigDirName)

    @Throws(IOException::class)
    fun write(json: String): File {
        val dir = directory.toPath()
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir, ownerOnly(OwnerOnlyDirPermissions))
        }
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(OwnerOnlyDirPermissions))
        // A crash can leave a file behind. No runtime is active at the first write after process start,
        // so drop every leftover once; later writes must not touch a file another start still reads.
        if (staleSwept.compareAndSet(false, true)) {
            directory.listFiles()?.forEach { it.delete() }
        }
        val file = dir.resolve("${UUID.randomUUID()}.json")
        // Created with mode 0600 before any byte is written.
        Files.createFile(file, ownerOnly(OwnerOnlyFilePermissions))
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(OwnerOnlyFilePermissions))
            Files.write(file, json.toByteArray(Charsets.UTF_8), StandardOpenOption.WRITE)
        } catch (error: IOException) {
            delete(file.toFile())
            throw error
        }
        return file.toFile()
    }

    private fun ownerOnly(mode: String) = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode))

    /** Idempotent; a missing file is success. */
    fun delete(file: File?) {
        if (file == null) return
        runCatching { Files.deleteIfExists(file.toPath()) }
        if (file.exists()) {
            file.delete()
        }
    }
}
