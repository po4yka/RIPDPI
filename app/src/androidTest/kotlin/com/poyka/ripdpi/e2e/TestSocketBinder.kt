package com.poyka.ripdpi.e2e

import android.os.Bundle

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
            payload.encodeToByteArray(),
            connectTimeoutMs,
            readTimeoutMs,
            device.encodeToByteArray(),
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
        nativeUdpRoundTrip(host, port, payload.encodeToByteArray(), timeoutMs, device.encodeToByteArray()),
    )

    private fun writeResult(
        extras: Bundle,
        result: Array<String>?,
    ) {
        check(result?.size == ResultWidth) { "Unexpected native socket probe result width" }
        extras.putBoolean(ExtraOk, result[ResultOk].toBoolean())
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
        value?.let { putString(key, it) }
    }

    private fun Bundle.putIntOrSkip(
        key: String,
        value: String?,
    ) {
        value?.toInt()?.let { putInt(key, it) }
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
