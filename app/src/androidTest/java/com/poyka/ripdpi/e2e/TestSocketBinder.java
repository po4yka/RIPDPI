package com.poyka.ripdpi.e2e;

import android.os.Bundle;
import java.nio.charset.StandardCharsets;

final class TestSocketBinder {
    private static final int RESULT_OK = 0;
    private static final int RESULT_RESPONSE = 1;
    private static final int RESULT_BOUND_DEVICE = 2;
    private static final int RESULT_FAILURE_KIND = 3;
    private static final int RESULT_ERRNO = 4;
    private static final int RESULT_LOCAL_PORT = 5;
    private static final int RESULT_FAILURE_STAGE = 6;
    private static final int RESULT_WIDTH = 7;
    private static final String EXTRA_OK = "ok";
    private static final String EXTRA_RESPONSE = "response";
    private static final String EXTRA_BOUND_DEVICE = "bound_device";
    private static final String EXTRA_FAILURE_KIND = "failure_kind";
    private static final String EXTRA_ERRNO = "errno";
    private static final String EXTRA_LOCAL_PORT = "local_port";
    private static final String EXTRA_FAILURE_STAGE = "failure_stage";

    static {
        System.loadLibrary("test_socket_binder");
    }

    private TestSocketBinder() {}

    static void tcpRoundTrip(
            String host,
            int port,
            String payload,
            int connectTimeoutMs,
            int readTimeoutMs,
            String device,
            Bundle extras) {
        writeResult(
                extras,
                nativeTcpRoundTrip(
                        host,
                        port,
                        payload.getBytes(StandardCharsets.UTF_8),
                        connectTimeoutMs,
                        readTimeoutMs,
                        device.getBytes(StandardCharsets.UTF_8)));
    }

    static void udpRoundTrip(
            String host,
            int port,
            String payload,
            int timeoutMs,
            String device,
            Bundle extras) {
        writeResult(
                extras,
                nativeUdpRoundTrip(
                        host,
                        port,
                        payload.getBytes(StandardCharsets.UTF_8),
                        timeoutMs,
                        device.getBytes(StandardCharsets.UTF_8)));
    }

    private static void writeResult(Bundle extras, String[] result) {
        if (result == null || result.length != RESULT_WIDTH) {
            throw new IllegalStateException("Unexpected native socket probe result width");
        }
        extras.putBoolean(EXTRA_OK, Boolean.parseBoolean(result[RESULT_OK]));
        putString(extras, EXTRA_RESPONSE, result[RESULT_RESPONSE]);
        putString(extras, EXTRA_BOUND_DEVICE, result[RESULT_BOUND_DEVICE]);
        putString(extras, EXTRA_FAILURE_KIND, result[RESULT_FAILURE_KIND]);
        putInteger(extras, EXTRA_ERRNO, result[RESULT_ERRNO]);
        putInteger(extras, EXTRA_LOCAL_PORT, result[RESULT_LOCAL_PORT]);
        putString(extras, EXTRA_FAILURE_STAGE, result[RESULT_FAILURE_STAGE]);
    }

    private static void putString(Bundle extras, String key, String value) {
        if (value != null) {
            extras.putString(key, value);
        }
    }

    private static void putInteger(Bundle extras, String key, String value) {
        if (value != null) {
            extras.putInt(key, Integer.parseInt(value));
        }
    }

    private static native String[] nativeTcpRoundTrip(
            String host,
            int port,
            byte[] payload,
            int connectTimeoutMs,
            int readTimeoutMs,
            byte[] deviceUtf8);

    private static native String[] nativeUdpRoundTrip(
            String host, int port, byte[] payload, int timeoutMs, byte[] deviceUtf8);
}
