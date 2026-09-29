package com.poyka.ripdpi.services

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.utility.shellSplit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal data class NfqwsBackendSession(
    val args: List<String>,
    val protectPath: String?,
    val noncePath: String,
    val running: Boolean,
)

internal fun nfqwsArguments(settings: AppSettings): List<String>? {
    val args = if (settings.enableCmdSettings) shellSplit(settings.cmdArgs) else emptyList()
    if (args.firstOrNull() != "nfqws2") return null
    require(settings.rootModeEnabled) { "nfqws2 requires root mode" }
    require(settings.strategyChainYaml.isBlank()) { "nfqws2 and the payload strategy chain cannot run together" }
    return args.drop(1)
}

internal object NfqwsBackendAssets {
    private val scripts =
        listOf(
            "zapret-lib.lua",
            "zapret-antidpi.lua",
            "zapret-auto.lua",
            "zapret-obfs.lua",
            "zapret-pcap.lua",
            "zapret-tests.lua",
        )

    @Synchronized
    fun extract(context: Context): File {
        val directory = File(context.filesDir, "nfqws2").apply { mkdirs() }
        val binary = File(directory, "nfqws2")
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: throw IOException("No supported Android ABI")
        copyAsset(context, "bin/$abi/nfqws2", binary)
        if (!binary.setExecutable(true, true)) throw IOException("Cannot make nfqws2 executable")
        copyTree(context, "zapret2", directory)
        return directory
    }

    fun initializers(): List<String> = scripts.map { "--lua-init=@lua/$it" }

    private fun copyTree(
        context: Context,
        asset: String,
        target: File,
    ) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            copyAsset(context, asset, target)
        } else {
            target.mkdirs()
            children.forEach { copyTree(context, "$asset/$it", File(target, it)) }
        }
    }

    private fun copyAsset(
        context: Context,
        asset: String,
        target: File,
    ) {
        target.parentFile?.mkdirs()
        val pending = File(target.parentFile, "${target.name}.pending")
        try {
            context.assets.open(asset).use { input -> pending.outputStream().use(input::copyTo) }
            if (!pending.renameTo(target)) throw IOException("Cannot install nfqws2 asset")
        } finally {
            pending.delete()
        }
    }
}

/** The helper uses a bounded big-endian length frame, not newline-delimited JSON. */
internal object RootHelperIpc {
    private const val Version = 3
    private const val MaxMessageBytes = 8192
    private const val StartTimeoutMillis = 15000
    private const val RequestTimeoutMillis = 2000
    private val ValidNonce = Regex("[A-Za-z0-9_-]{32,128}")

    fun shutdown(
        socketPath: String,
        noncePath: String?,
    ) {
        noncePath?.let { request(socketPath, it, "v3/shutdown", buildJsonObject {}) }
    }

    fun request(
        socketPath: String,
        noncePath: String,
        command: String,
        params: JsonObject,
    ): JsonObject {
        val nonce = File(noncePath).readText(Charsets.US_ASCII).trim()
        if (!ValidNonce.matches(nonce)) throw IOException("Invalid root-helper nonce")
        val payload =
            buildJsonObject {
                put("protocol_version", Version)
                put("command", command)
                put("session_nonce", nonce)
                put("params", params)
            }.toString().toByteArray(Charsets.UTF_8)
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = if (command == "v3/start_nfqws2") StartTimeoutMillis else RequestTimeoutMillis
            writeFrame(socket.outputStream, payload)
            return parseResponse(readFrame(socket.inputStream))
        }
    }

    private fun parseResponse(payload: ByteArray): JsonObject {
        val response = Json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
        if (response["protocol_version"]?.jsonPrimitive?.content != Version.toString()) {
            throw IOException("Unsupported root-helper protocol")
        }
        if (response["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw IOException(response["error"]?.jsonPrimitive?.content ?: "Root-helper request failed")
        }
        return response["data"] as? JsonObject ?: buildJsonObject {}
    }

    fun writeFrame(
        output: OutputStream,
        payload: ByteArray,
    ) {
        if (payload.size !in 1..MaxMessageBytes) throw IOException("Invalid root-helper frame length")
        val framed = DataOutputStream(output)
        framed.writeInt(payload.size)
        framed.write(payload)
        framed.flush()
    }

    fun readFrame(input: InputStream): ByteArray {
        val framed = DataInputStream(input)
        val length = framed.readInt()
        if (length !in 1..MaxMessageBytes) throw IOException("Invalid root-helper frame length")
        return ByteArray(length).also(framed::readFully)
    }
}
