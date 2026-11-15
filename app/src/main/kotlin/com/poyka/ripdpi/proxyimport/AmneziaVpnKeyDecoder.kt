package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.awg.isAwgNumericAddress
import com.poyka.ripdpi.data.wireguard.WireGuardConfParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Extracts the WireGuard / AmneziaWG `.conf` text from an AmneziaVPN share key.
 *
 * AmneziaVPN (amnezia-client 5.x) shares a server as `vpn://` + base64url(`qCompress(json)`),
 * where `qCompress` output is a 4-byte big-endian length followed by a zlib stream
 * (`exportController.cpp`, `importController.cpp`). Long keys are also shown as a series of
 * QR codes; [AmneziaQrChunkAssembler] rebuilds the same compressed bytes from them. The JSON
 * lists `containers`; an `amnezia-awg`, `amnezia-awg2` (key `awg`) or `amnezia-wireguard`
 * (key `wireguard`) container holds `last_config`, a JSON string whose `config` field is the
 * native `.conf`. `$PRIMARY_DNS` / `$SECONDARY_DNS` placeholders are filled from the
 * top-level `dns1` / `dns2`, as AmneziaVPN does on export.
 *
 * Keys for other protocols (OpenVPN, Xray, Amnezia Premium API keys) yield `null`.
 * Never throws; decompression is bounded so a crafted key cannot exhaust memory.
 */
internal object AmneziaVpnKeyDecoder {
    const val Scheme = "vpn://"

    fun confFromKey(text: String): String? =
        text
            .takeIf { it.startsWith(Scheme, ignoreCase = true) }
            ?.let { key -> key.substring(Scheme.length).filterNot { it.isWhitespace() } }
            ?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }
            // AmneziaVPN also accepts an uncompressed JSON key in the explicit vpn:// form.
            ?.let { confFromJson(qUncompress(it) ?: it) }

    fun confFromCompressed(compressed: ByteArray): String? =
        compressed.takeIf { it.size <= MaxDecompressedBytes }?.let(::qUncompress)?.let(::confFromJson)

    private fun confFromJson(bytes: ByteArray): String? {
        val root =
            bytes
                .takeIf { it.size <= MaxDecompressedBytes }
                ?.let(::decodeUtf8OrNull)
                ?.let { json -> runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() }
                ?: return null
        return nativeConfOf(root)?.takeIf(::isWithinImportTextLimit)?.let { conf -> root.expandDns(conf) }
    }

    private fun JsonObject.expandDns(conf: String): String? {
        val primary = dnsReplacement(conf, PrimaryDns, "dns1")
        val secondary = dnsReplacement(conf, SecondaryDns, "dns2")
        if (primary == null || secondary == null) return null
        return conf
            .replace(PrimaryDns, primary)
            .replace(SecondaryDns, secondary)
            .lineSequence()
            .mapNotNull(::withoutEmptyDnsEntries)
            .joinToString("\n")
            .takeIf(::isWithinImportTextLimit)
    }

    private fun JsonObject.dnsReplacement(
        conf: String,
        placeholder: String,
        key: String,
    ): String? {
        if (placeholder !in conf) return ""
        val value = string(key)?.trim().orEmpty()
        return value.takeIf { it.isEmpty() || (it.length <= MaxDnsLiteralChars && isAwgNumericAddress(it)) }
    }

    /** An unset DNS placeholder leaves an empty entry (`DNS = 1.1.1.1, `) or an empty line behind. */
    private fun withoutEmptyDnsEntries(line: String): String? {
        val key = line.substringBefore('=').trim()
        if (!key.equals("DNS", ignoreCase = true) || '=' !in line) return line
        val servers =
            line
                .substringAfter('=')
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        return if (servers.isEmpty()) null else "DNS = " + servers.joinToString(", ")
    }

    private fun nativeConfOf(root: JsonObject): String? {
        val containers = root["containers"] as? JsonArray ?: return null
        val candidates = containers.mapNotNull { it as? JsonObject }
        val preferred = root.string("defaultContainer")
        val ordered = candidates.sortedByDescending { it.string("container") == preferred }
        return ordered.firstNotNullOfOrNull { container ->
            val key = ContainerProtocolKeys[container.string("container")] ?: return@firstNotNullOfOrNull null
            val lastConfig = (container[key] as? JsonObject)?.string("last_config") ?: return@firstNotNullOfOrNull null
            runCatching { (Json.parseToJsonElement(lastConfig) as? JsonObject)?.string("config") }
                .getOrNull()
                ?.takeIf(WireGuardConfParser::looksLikeConf)
        }
    }

    /** Qt `qUncompress`: 4-byte big-endian expected size, then a zlib stream. */
    private fun qUncompress(data: ByteArray): ByteArray? {
        val expected = if (data.size > QtLengthPrefixBytes) ByteBuffer.wrap(data, 0, QtLengthPrefixBytes).int else 0
        return if (expected in 1..MaxDecompressedBytes) inflateExactly(data, expected) else null
    }

    private fun inflateExactly(
        data: ByteArray,
        expected: Int,
    ): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data, QtLengthPrefixBytes, data.size - QtLengthPrefixBytes)
            val out = ByteArrayOutputStream(expected)
            val buffer = ByteArray(InflateChunkBytes)
            while (!inflater.finished() && out.size() <= expected) {
                val read = inflater.inflate(buffer)
                if (read == 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray().takeIf { inflater.finished() && inflater.remaining == 0 && it.size == expected }
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val ContainerProtocolKeys =
        mapOf("amnezia-awg" to "awg", "amnezia-awg2" to "awg", "amnezia-wireguard" to "wireguard")
    private const val PrimaryDns = "\$PRIMARY_DNS"
    private const val SecondaryDns = "\$SECONDARY_DNS"
    private const val MaxDnsLiteralChars = 45
    private const val QtLengthPrefixBytes = 4
    private const val MaxDecompressedBytes = 256 * 1024
    private const val InflateChunkBytes = 8 * 1024
}

/**
 * Collects AmneziaVPN QR-series chunks. Each code is base64url of a Qt `QDataStream`:
 * `qint16` magic 1984, `quint8` chunk count, `quint8` chunk index, then a `QByteArray`
 * (`quint32` length + bytes) holding a slice of the compressed key (`qrCodeUtils.cpp`,
 * `ImportController::parseQrCodeChunk`). Not thread-safe; owned by one scanner session.
 */
class AmneziaQrChunkAssembler {
    /** Progress of a partially scanned series. */
    data class Progress(
        val received: Int,
        val total: Int,
    )

    sealed interface Result {
        data class Partial(
            val progress: Progress,
        ) : Result

        data class Complete(
            val compressed: ByteArray,
        ) : Result

        /** A code of the series that was just completed; the camera still sees it. */
        data object Repeat : Result

        /** Two different chunks claim the same index. Discard the incomplete series. */
        data object Conflict : Result
    }

    private val chunks = sortedMapOf<Int, ByteArray>()
    private var total = 0
    private val digest = MessageDigest.getInstance("SHA-256")
    private var completedChunks = emptyMap<Int, ByteArray>()

    /** Returns `null` when [payload] is not an AmneziaVPN QR chunk. */
    fun accept(payload: String): Result? = parse(payload)?.let(::add)

    private fun add(chunk: Chunk): Result {
        if (chunk.total == completedChunks.size &&
            completedChunks[chunk.index]?.contentEquals(digest.digest(chunk.data)) == true
        ) {
            return Result.Repeat
        }
        completedChunks = emptyMap()
        if (chunk.total != total) {
            chunks.clear()
            total = chunk.total
        }
        val accepted = chunks.putIfAbsent(chunk.index, chunk.data)
        return when {
            accepted != null && !accepted.contentEquals(chunk.data) -> {
                chunks.clear()
                Result.Conflict
            }

            chunks.size < total -> {
                Result.Partial(Progress(chunks.size, total))
            }

            else -> {
                finishSeries()
            }
        }
    }

    private fun finishSeries(): Result.Complete {
        val assembled = ByteArrayOutputStream()
        chunks.values.forEach(assembled::write)
        completedChunks = chunks.mapValues { (_, bytes) -> digest.digest(bytes) }
        chunks.clear()
        total = 0
        return Result.Complete(assembled.toByteArray())
    }

    private class Chunk(
        val total: Int,
        val index: Int,
        val data: ByteArray,
    )

    // Early returns read best for a byte-format parser.
    @Suppress("ReturnCount")
    private fun parse(payload: String): Chunk? {
        if (payload.length > MaxEncodedChunkLength) return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(payload.trim()) }.getOrNull() ?: return null
        if (bytes.size < HeaderBytes) return null
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.short.toInt() != MagicCode) return null
        val count = buffer.get().toInt() and UByteMask
        val index = buffer.get().toInt() and UByteMask
        val length = buffer.int
        val validHeader = index < count && length > 0 && length == buffer.remaining()
        return if (validHeader) Chunk(count, index, ByteArray(length).also(buffer::get)) else null
    }

    private companion object {
        const val MagicCode = 1984
        const val UByteMask = 0xFF
        const val HeaderBytes = 2 + 1 + 1 + 4

        // Upstream uses 850 data bytes per chunk. Cap the input before Base64 allocates.
        const val MaxEncodedChunkLength = 4096
    }
}
