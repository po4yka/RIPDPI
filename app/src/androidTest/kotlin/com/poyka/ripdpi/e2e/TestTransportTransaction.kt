package com.poyka.ripdpi.e2e

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.Timer
import java.util.TimerTask
import java.util.TreeMap
import java.util.regex.Pattern

/** The standalone test APK process must use only Android and Java runtime APIs. */
internal fun transportTransaction(
    operation: String,
    id: String,
    size: Int,
    seconds: Int,
    port: Int,
): Bundle {
    require("download".equals(operation) || "upload".equals(operation) || "stream".equals(operation))
    require(Pattern.matches("[a-z0-9][a-z0-9_-]{7,63}", id))
    require(port in 1..65_535)
    require(size in 1..8 * 1024 * 1024 && seconds in 1..120)
    val expectedSize = if ("stream".equals(operation)) (seconds + 1) * 65_536 else size
    val payload = ByteArray(expectedSize)
    var payloadIndex = 0
    while (payloadIndex < payload.size) {
        payload[payloadIndex] = (payloadIndex % 251).toByte()
        payloadIndex += 1
    }
    val expectedDigest = transportDigest(payload)
    val started = SystemClock.elapsedRealtime()
    val result = Bundle()
    result.putInt("uid", Process.myUid())
    result.putString("id", id)
    val timer = Timer("transport-deadline", true)
    val socket = Socket()
    timer.schedule(
        object : TimerTask() {
            override fun run() {
                socket.close()
            }
        },
        (seconds + 30L) * 1000L,
    )
    try {
        socket.connect(InetSocketAddress("192.0.2.77", port), 5_000)
        socket.soTimeout = 10_000
        val output = socket.getOutputStream()
        val path = "/transport/$operation?id=$id&bytes=$size&seconds=$seconds"
        val headers =
            if ("upload".equals(operation)) {
                "POST $path HTTP/1.1\r\nContent-Length: $expectedSize\r\nX-Content-SHA256: $expectedDigest\r\n"
            } else {
                "GET $path HTTP/1.1\r\n"
            }
        val encodedHeaders = StandardCharsets.UTF_8.encode(headers + "Host: fixture.test\r\nConnection: close\r\n\r\n")
        val headerBytes = ByteArray(encodedHeaders.remaining())
        encodedHeaders.get(headerBytes)
        output.write(headerBytes)
        if ("upload".equals(operation)) output.write(payload)
        output.flush()
        val input = BufferedInputStream(socket.getInputStream())
        check(Pattern.matches("HTTP/1\\.[01] 200.*", input.transportLine())) { "HTTP transaction rejected" }
        val responseHeaders = TreeMap<String, String>(java.lang.String.CASE_INSENSITIVE_ORDER)
        var headerCount = 0
        while (true) {
            check(++headerCount <= 64) { "Too many HTTP headers" }
            val line = input.transportLine()
            if (line.length == 0) break
            var separator = 0
            while (separator < line.length && line[separator] != ':') separator += 1
            check(separator > 0 && separator < line.length)
            responseHeaders.put(
                transportSlice(line, 0, separator),
                transportTrim(transportSlice(line, separator + 1, line.length)),
            )
        }
        check(responseHeaders.get("transfer-encoding") == null) { "Expected bounded Content-Length" }
        val length = Integer.parseInt(requireNotNull(responseHeaders.get("content-length")))
        check(length in 1..8 * 1024 * 1024)
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            check(count > 0) { "Truncated HTTP body" }
            offset += count
        }
        if ("upload".equals(operation)) {
            check(length <= 4096)
            val receipt = JSONObject(String(body, StandardCharsets.UTF_8))
            check(id.equals(receipt.getString("id")) && receipt.getInt("bytes") == expectedSize)
            check(expectedDigest.equals(receipt.getString("sha256")))
        } else {
            check(length == expectedSize && Arrays.equals(body, payload)) { "HTTP payload mismatch" }
            check(expectedDigest.equals(transportDigest(body)))
            check(expectedDigest.equals(responseHeaders.get("x-content-sha256")))
        }
        result.putBoolean("ok", true)
        result.putInt("bytes", expectedSize)
        result.putString("sha256", expectedDigest)
    } catch (error: Exception) {
        result.putBoolean("ok", false)
        val message = error.message ?: ""
        result.putString(
            "error",
            error.javaClass.simpleName + ": " + transportSlice(message, 0, Math.min(message.length, 160)),
        )
    } finally {
        timer.cancel()
        socket.close()
        result.putLong("elapsedMs", SystemClock.elapsedRealtime() - started)
    }
    return result
}

private fun InputStream.transportLine(): String {
    val line = StringBuilder()
    while (line.length <= 4096) {
        val next = read()
        check(next >= 0) { "Truncated HTTP headers" }
        if (next == 10) {
            if (line.length > 0 && line[line.length - 1] == '\r') line.deleteCharAt(line.length - 1)
            return line.toString()
        }
        line.append(next.toChar())
    }
    error("HTTP header exceeded bound")
}

private fun transportTrim(value: String): String {
    var first = 0
    var last = value.length
    while (first < last && value[first] <= ' ') first += 1
    while (last > first && value[last - 1] <= ' ') last -= 1
    return transportSlice(value, first, last)
}

private fun transportSlice(
    value: String,
    first: Int,
    last: Int,
): String = StringBuilder(last - first).append(value, first, last).toString()

internal fun transportDigest(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val encoded = StringBuilder(digest.size * 2)
    var index = 0
    while (index < digest.size) {
        val value = digest[index].toInt() and 255
        if (value < 16) encoded.append('0')
        encoded.append(Integer.toHexString(value))
        index += 1
    }
    return encoded.toString()
}
