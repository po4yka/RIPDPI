package com.poyka.ripdpi.e2e;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class TestNetworkProbeReceiver extends BroadcastReceiver {
    private static final String ACTION_PROBE_TCP = "com.poyka.ripdpi.debug.PROBE_TCP";
    private static final String ACTION_PROBE_DNS = "com.poyka.ripdpi.debug.PROBE_DNS";
    private static final String EXTRA_HOST = "host";
    private static final String EXTRA_PORT = "port";
    private static final String EXTRA_CONNECT_TIMEOUT_MS = "connect_timeout_ms";
    private static final String EXTRA_READ_TIMEOUT_MS = "read_timeout_ms";
    private static final String EXTRA_PAYLOAD = "payload";
    private static final String EXTRA_QUERY_HOST = "query_host";
    private static final String EXTRA_OK = "ok";
    private static final String EXTRA_LOCAL_ADDRESS = "local_address";
    private static final String EXTRA_LOCAL_PORT = "local_port";
    private static final String EXTRA_RESPONSE = "response";
    private static final String EXTRA_DNS_RCODE = "rcode";
    private static final String EXTRA_DNS_ANSWERS = "answers";
    private static final String EXTRA_DNS_LATENCY_MS = "latency_ms";
    private static final String EXTRA_ERROR_CLASS = "error_class";
    private static final String EXTRA_ERROR_MESSAGE = "error_message";
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 5_000;
    private static final int DNS_PACKET_MAX_BYTES = 1_500;
    private static final int DNS_POINTER_MASK = 0xC0;
    private static final int DNS_POINTER_VALUE = 0xC0;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!ACTION_PROBE_TCP.equals(action) && !ACTION_PROBE_DNS.equals(action)) {
            return;
        }

        PendingResult pendingResult = goAsync();
        Thread worker =
                new Thread(
                        () -> {
                            Bundle extras = new Bundle();
                            int resultCode;
                            try {
                                if (ACTION_PROBE_DNS.equals(action)) {
                                    runDnsProbe(intent, extras);
                                } else {
                                    runTcpProbe(intent, extras);
                                }
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

    private static void runDnsProbe(Intent intent, Bundle extras) throws Exception {
        String serverHost = intent.getStringExtra(EXTRA_HOST);
        int serverPort = intent.getIntExtra(EXTRA_PORT, -1);
        int timeoutMs = intent.getIntExtra(EXTRA_READ_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
        String queryHost = intent.getStringExtra(EXTRA_QUERY_HOST);

        if (serverHost == null || serverHost.isBlank()) {
            throw new IllegalArgumentException("Missing host extra");
        }
        if (serverPort < 1 || serverPort > 65_535) {
            throw new IllegalArgumentException("Invalid port extra: " + serverPort);
        }
        if (queryHost == null || queryHost.isBlank()) {
            throw new IllegalArgumentException("Missing query host extra");
        }

        int requestId = ThreadLocalRandom.current().nextInt(0x1_0000);
        byte[] query = buildDnsQuery(queryHost, requestId);
        long startedAt = SystemClock.elapsedRealtime();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            socket.connect(new InetSocketAddress(serverHost, serverPort));
            if (socket.getLocalAddress() != null) {
                extras.putString(EXTRA_LOCAL_ADDRESS, socket.getLocalAddress().getHostAddress());
            }
            extras.putInt(EXTRA_LOCAL_PORT, socket.getLocalPort());

            socket.send(new DatagramPacket(query, query.length));

            byte[] responseBytes = new byte[DNS_PACKET_MAX_BYTES];
            DatagramPacket incomingPacket = new DatagramPacket(responseBytes, responseBytes.length);
            socket.receive(incomingPacket);

            DecodedDnsResponse decoded =
                    decodeDnsResponse(Arrays.copyOf(responseBytes, incomingPacket.getLength()), requestId);
            extras.putBoolean(EXTRA_OK, true);
            extras.putInt(EXTRA_DNS_RCODE, decoded.rcode);
            extras.putStringArrayList(EXTRA_DNS_ANSWERS, new ArrayList<>(decoded.answers));
            extras.putLong(EXTRA_DNS_LATENCY_MS, SystemClock.elapsedRealtime() - startedAt);
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

    private static byte[] buildDnsQuery(String hostname, int requestId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeU16(output, requestId);
        writeU16(output, 0x0100);
        writeU16(output, 1);
        writeU16(output, 0);
        writeU16(output, 0);
        writeU16(output, 0);
        for (String label : hostname.split("\\.")) {
            if (label.isEmpty()) {
                throw new IllegalArgumentException("hostname contains an empty label: " + hostname);
            }
            byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
            if (labelBytes.length > 63) {
                throw new IllegalArgumentException("hostname label exceeds 63 octets: " + label);
            }
            output.write(labelBytes.length);
            output.write(labelBytes, 0, labelBytes.length);
        }
        output.write(0);
        writeU16(output, 1);
        writeU16(output, 1);
        return output.toByteArray();
    }

    private static DecodedDnsResponse decodeDnsResponse(byte[] packet, int expectedRequestId) throws Exception {
        if (packet.length < 12) {
            throw new IllegalArgumentException("DNS packet too short: " + packet.length);
        }
        int requestId = readU16(packet, 0);
        if (requestId != expectedRequestId) {
            throw new IllegalArgumentException(
                    "Unexpected DNS request ID: expected=" + expectedRequestId + " actual=" + requestId);
        }
        int flags = readU16(packet, 2);
        int questionCount = readU16(packet, 4);
        int answerCount = readU16(packet, 6);
        int offset = 12;
        for (int question = 0; question < questionCount; question += 1) {
            offset = skipName(packet, offset);
            requireAvailable(packet, offset, 4, "DNS question section truncated");
            offset += 4;
        }

        ArrayList<String> answers = new ArrayList<>();
        for (int answer = 0; answer < answerCount; answer += 1) {
            offset = skipName(packet, offset);
            requireAvailable(packet, offset, 10, "DNS answer section truncated");
            int type = readU16(packet, offset);
            int dataLength = readU16(packet, offset + 8);
            offset += 10;
            requireAvailable(packet, offset, dataLength, "DNS rdata section truncated");
            if (type == 1 && dataLength == 4 || type == 28 && dataLength == 16) {
                answers.add(InetAddress.getByAddress(Arrays.copyOfRange(packet, offset, offset + dataLength))
                        .getHostAddress());
            } else if (type == 5 || type == 12) {
                answers.add(readName(packet, offset).name);
            }
            offset += dataLength;
        }

        return new DecodedDnsResponse(flags & 0x000F, answers);
    }

    private static int skipName(byte[] packet, int offset) {
        int current = offset;
        while (true) {
            requireAvailable(packet, current, 1, "DNS name truncated");
            int length = packet[current] & 0xFF;
            if (length == 0) {
                return current + 1;
            }
            if ((length & DNS_POINTER_MASK) == DNS_POINTER_VALUE) {
                requireAvailable(packet, current, 2, "DNS compression pointer truncated");
                return current + 2;
            }
            if ((length & DNS_POINTER_MASK) != 0) {
                throw new IllegalArgumentException("Unsupported DNS label prefix: " + length);
            }
            current += 1;
            requireAvailable(packet, current, length, "DNS label truncated");
            current += length;
        }
    }

    private static DnsNameResult readName(byte[] packet, int offset) {
        StringBuilder builder = new StringBuilder();
        int current = offset;
        int consumedOffset = -1;
        for (int jumps = 0; jumps < packet.length; jumps += 1) {
            requireAvailable(packet, current, 1, "DNS name truncated");
            int length = packet[current] & 0xFF;
            if (length == 0) {
                return new DnsNameResult(builder.toString(), consumedOffset == -1 ? current + 1 : consumedOffset);
            }
            if ((length & DNS_POINTER_MASK) == DNS_POINTER_VALUE) {
                requireAvailable(packet, current, 2, "DNS compression pointer truncated");
                int pointer = ((length & 0x3F) << 8) | (packet[current + 1] & 0xFF);
                if (consumedOffset == -1) {
                    consumedOffset = current + 2;
                }
                current = pointer;
                continue;
            }
            if ((length & DNS_POINTER_MASK) != 0) {
                throw new IllegalArgumentException("Unsupported DNS label prefix: " + length);
            }
            current += 1;
            requireAvailable(packet, current, length, "DNS label truncated");
            if (builder.length() > 0) {
                builder.append('.');
            }
            builder.append(new String(packet, current, length, StandardCharsets.UTF_8));
            current += length;
        }
        throw new IllegalArgumentException("DNS name compression loop");
    }

    private static int readU16(byte[] packet, int offset) {
        requireAvailable(packet, offset, 2, "u16 field truncated");
        return ((packet[offset] & 0xFF) << 8) | (packet[offset + 1] & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream output, int value) {
        output.write((value >> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static void requireAvailable(byte[] packet, int offset, int length, String message) {
        if (offset < 0 || length < 0 || offset + length > packet.length) {
            throw new IllegalArgumentException(message);
        }
    }

    private static final class DecodedDnsResponse {
        final int rcode;
        final List<String> answers;

        DecodedDnsResponse(int rcode, List<String> answers) {
            this.rcode = rcode;
            this.answers = answers;
        }
    }

    private static final class DnsNameResult {
        final String name;
        final int nextOffset;

        DnsNameResult(String name, int nextOffset) {
            this.name = name;
            this.nextOffset = nextOffset;
        }
    }
}
