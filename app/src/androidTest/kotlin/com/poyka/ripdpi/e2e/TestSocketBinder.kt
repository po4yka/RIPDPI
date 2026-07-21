package com.poyka.ripdpi.e2e

import android.os.Bundle
import java.nio.charset.StandardCharsets

internal object TestSocketBinder {
    private const val ResultOk = 0
    private const val ResultResponse = 1
    private const val ResultBoundDevice = 2
    private const val ResultFailureKind = 3
    private const val ResultErrno = 4
    private const val ResultLocalPort = 5
    private const val ResultFailureStage = 6
    private const val ResultWidth = 7

    private const val ExtraOk = "ok"
    private const val ExtraResponse = "response"
    private const val ExtraBoundDevice = "bound_device"
    private const val ExtraFailureKind = "failure_kind"
    private const val ExtraErrno = "errno"
    private const val ExtraLocalPort = "local_port"
    private const val ExtraFailureStage = "failure_stage"

    init {
        System.loadLibrary("test_socket_binder")
    }

    fun tcpRoundTrip(
        host: String,
        port: Int,
        payload: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        device: String,
        extras: Bundle,
    ) = writeResult(
        extras,
        nativeTcpRoundTrip(
            host,
            port,
            payload.utf8Bytes(),
            connectTimeoutMs,
            readTimeoutMs,
            device.utf8Bytes(),
        ),
    )

    fun udpRoundTrip(
        host: String,
        port: Int,
        payload: String,
        timeoutMs: Int,
        device: String,
        extras: Bundle,
    ) = writeResult(
        extras,
        nativeUdpRoundTrip(host, port, payload.utf8Bytes(), timeoutMs, device.utf8Bytes()),
    )

    private fun String.utf8Bytes(): ByteArray {
        val encoded = StandardCharsets.UTF_8.encode(this)
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        return bytes
    }

    private fun writeResult(
        extras: Bundle,
        result: Array<String>?,
    ) {
        check(result?.size == ResultWidth) { "Unexpected native socket probe result width" }
        extras.putBoolean(ExtraOk, java.lang.Boolean.parseBoolean(result[ResultOk]))
        extras.putStringOrSkip(ExtraResponse, result[ResultResponse])
        extras.putStringOrSkip(ExtraBoundDevice, result[ResultBoundDevice])
        extras.putStringOrSkip(ExtraFailureKind, result[ResultFailureKind])
        extras.putIntOrSkip(ExtraErrno, result[ResultErrno])
        extras.putIntOrSkip(ExtraLocalPort, result[ResultLocalPort])
        extras.putStringOrSkip(ExtraFailureStage, result[ResultFailureStage])
    }

    private fun Bundle.putStringOrSkip(
        key: String,
        value: String?,
    ) {
        if (value != null) {
            putString(key, value)
        }
    }

    private fun Bundle.putIntOrSkip(
        key: String,
        value: String?,
    ) {
        if (value != null) {
            putInt(key, Integer.parseInt(value))
        }
    }

    @JvmStatic
    private external fun nativeTcpRoundTrip(
        host: String,
        port: Int,
        payload: ByteArray,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        deviceUtf8: ByteArray,
    ): Array<String>?

    @JvmStatic
    private external fun nativeUdpRoundTrip(
        host: String,
        port: Int,
        payload: ByteArray,
        timeoutMs: Int,
        deviceUtf8: ByteArray,
    ): Array<String>?
}
