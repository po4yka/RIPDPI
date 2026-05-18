package com.poyka.ripdpi.e2e;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public final class TestNetworkProbeReceiver extends BroadcastReceiver {
    private static final String ACTION_PROBE_TCP = "com.poyka.ripdpi.debug.PROBE_TCP";
    private static final String EXTRA_HOST = "host";
    private static final String EXTRA_PORT = "port";
    private static final String EXTRA_CONNECT_TIMEOUT_MS = "connect_timeout_ms";
    private static final String EXTRA_READ_TIMEOUT_MS = "read_timeout_ms";
    private static final String EXTRA_PAYLOAD = "payload";
    private static final String EXTRA_OK = "ok";
    private static final String EXTRA_LOCAL_ADDRESS = "local_address";
    private static final String EXTRA_LOCAL_PORT = "local_port";
    private static final String EXTRA_RESPONSE = "response";
    private static final String EXTRA_ERROR_CLASS = "error_class";
    private static final String EXTRA_ERROR_MESSAGE = "error_message";
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 5_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_PROBE_TCP.equals(intent.getAction())) {
            return;
        }

        PendingResult pendingResult = goAsync();
        Thread worker =
                new Thread(
                        () -> {
                            Bundle extras = new Bundle();
                            int resultCode;
                            try {
                                runTcpProbe(intent, extras);
                                resultCode = Activity.RESULT_OK;
                            } catch (Throwable error) {
                                extras.putBoolean(EXTRA_OK, false);
                                extras.putString(EXTRA_ERROR_CLASS, error.getClass().getName());
                                extras.putString(EXTRA_ERROR_MESSAGE, error.getMessage());
                                resultCode = Activity.RESULT_CANCELED;
                            }

                            pendingResult.setResultCode(resultCode);
                            pendingResult.setResultExtras(extras);
                            pendingResult.finish();
                        },
                        "test-network-probe");
        worker.setDaemon(true);
        worker.start();
    }

    private static void runTcpProbe(Intent intent, Bundle extras) throws Exception {
        String host = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, -1);
        int connectTimeoutMs = intent.getIntExtra(EXTRA_CONNECT_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS);
        int readTimeoutMs = intent.getIntExtra(EXTRA_READ_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
        String payload = intent.getStringExtra(EXTRA_PAYLOAD);

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Missing host extra");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Invalid port extra: " + port);
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            extras.putBoolean(EXTRA_OK, true);
            if (socket.getLocalAddress() != null) {
                extras.putString(EXTRA_LOCAL_ADDRESS, socket.getLocalAddress().getHostAddress());
            }
            extras.putInt(EXTRA_LOCAL_PORT, socket.getLocalPort());

            if (payload != null) {
                byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
                OutputStream output = socket.getOutputStream();
                output.write(payloadBytes);
                output.flush();
                socket.shutdownOutput();

                InputStream input = socket.getInputStream();
                extras.putString(EXTRA_RESPONSE, readTcpProbeResponse(input, payloadBytes.length));
            }
        }
    }

    private static String readTcpProbeResponse(InputStream input, int expectedBytes) throws Exception {
        if (expectedBytes < 0) {
            throw new IllegalArgumentException("expectedBytes must be non-negative");
        }
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[4 * 1024];
        while (expectedBytes == 0 || response.size() < expectedBytes) {
            int maxRead = expectedBytes == 0 ? buffer.length : Math.min(buffer.length, expectedBytes - response.size());
            int read;
            try {
                read = input.read(buffer, 0, maxRead);
            } catch (SocketTimeoutException timeout) {
                if (response.size() > 0) {
                    break;
                }
                throw timeout;
            }
            if (read <= 0) {
                break;
            }
            response.write(buffer, 0, read);
        }
        return response.toString(StandardCharsets.UTF_8.name());
    }
}
