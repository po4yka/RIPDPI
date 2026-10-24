package com.poyka.ripdpi.e2e

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Timer
import java.util.TimerTask

/** Bounded real HTTP transfer in the test APK UID, through its default network. */
internal fun transportTransaction(
    operation: String,
    id: String,
    size: Int,
    seconds: Int,
    port: Int,
): Bundle {
    require(operation in setOf("download", "upload", "stream"))
    require(id.matches(Regex("[a-z0-9][a-z0-9_-]{7,63}")))
    require(port in 1..65_535)
    require(size in 1..8 * 1024 * 1024 && seconds in 1..120)
    val expectedSize = if (operation == "stream") (seconds + 1) * 65_536 else size
    val payload = ByteArray(expectedSize) { (it % 251).toByte() }
    val expectedDigest = transportDigest(payload)
    val started = SystemClock.elapsedRealtime()
    val result =
        Bundle().apply {
            putInt("uid", Process.myUid())
            putString("id", id)
        }
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
        socket.use {
            it.connect(InetSocketAddress("192.0.2.77", port), 5_000)
            it.soTimeout = 10_000
            val output = it.getOutputStream()
            val path = "/transport/$operation?id=$id&bytes=$size&seconds=$seconds"
            val headers =
                if (operation == "upload") {
                    "POST $path HTTP/1.1\r\nContent-Length: $expectedSize\r\nX-Content-SHA256: $expectedDigest\r\n"
                } else {
                    "GET $path HTTP/1.1\r\n"
                }
            output.write((headers + "Host: fixture.test\r\nConnection: close\r\n\r\n").toByteArray())
            if (operation == "upload") output.write(payload)
            output.flush()
            val input = BufferedInputStream(it.getInputStream())
            check(input.transportLine().matches(Regex("HTTP/1\\.[01] 200.*"))) { "HTTP transaction rejected" }
            val responseHeaders = mutableMapOf<String, String>()
            var headerCount = 0
            while (true) {
                check(++headerCount <= 64) { "Too many HTTP headers" }
                val line = input.transportLine()
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                check(separator > 0)
                responseHeaders[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
            check(responseHeaders["transfer-encoding"] == null) { "Expected bounded Content-Length" }
            val length = requireNotNull(responseHeaders["content-length"]).toInt()
            check(length in 1..8 * 1024 * 1024)
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(body, offset, length - offset)
                check(count > 0) { "Truncated HTTP body" }
                offset += count
            }
            if (operation == "upload") {
                check(length <= 4096)
                val receipt = JSONObject(body.decodeToString())
                check(receipt.getString("id") == id && receipt.getInt("bytes") == expectedSize)
                check(receipt.getString("sha256") == expectedDigest)
            } else {
                check(length == expectedSize && body.contentEquals(payload)) { "HTTP payload mismatch" }
                check(transportDigest(body) == expectedDigest)
                check(responseHeaders["x-content-sha256"] == expectedDigest)
            }
            result.putBoolean("ok", true)
            result.putInt("bytes", expectedSize)
            result.putString("sha256", expectedDigest)
        }
    } catch (error: Exception) {
        result.putBoolean("ok", false)
        result.putString("error", error.javaClass.simpleName + ": " + error.message.orEmpty().take(160))
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
        if (next == 10) return line.toString().removeSuffix("\r")
        line.append(next.toChar())
    }
    error("HTTP header exceeded bound")
}

internal fun transportDigest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
