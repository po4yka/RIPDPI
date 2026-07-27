#!/usr/bin/env python3
"""Serve the two source-owned AAAA observations used by the physical oracle."""

from __future__ import annotations

import argparse
import ipaddress
import socket
import struct
import threading


IPV4_ONLY_NAME = "ipv4-only.ordinary.fixture.test"
DUAL_STACK_NAME = "dual-stack.ordinary.fixture.test"


def parse_question(packet: bytes) -> tuple[int, str, int, int, int]:
    if len(packet) < 17:
        raise ValueError("DNS question is truncated")
    query_id, flags, questions, answers, authorities, additional = struct.unpack(
        "!HHHHHH", packet[:12]
    )
    if flags & 0x8000 or (questions, answers, authorities, additional) != (1, 0, 0, 0):
        raise ValueError("DNS packet is not one ordinary question")
    offset = 12
    labels: list[str] = []
    while True:
        if offset >= len(packet):
            raise ValueError("DNS name is truncated")
        length = packet[offset]
        offset += 1
        if length == 0:
            break
        if length > 63 or offset + length > len(packet):
            raise ValueError("DNS label is malformed")
        labels.append(packet[offset : offset + length].decode("ascii"))
        offset += length
    if offset + 4 != len(packet):
        raise ValueError("DNS question has trailing bytes")
    query_type, query_class = struct.unpack("!HH", packet[offset : offset + 4])
    return query_id, ".".join(labels).lower(), query_type, query_class, offset + 4


def build_response(packet: bytes, *, dual_stack_ipv6: str) -> bytes:
    query_id, name, query_type, query_class, question_end = parse_question(packet)
    if (
        query_type != 28
        or query_class != 1
        or name not in {IPV4_ONLY_NAME, DUAL_STACK_NAME}
    ):
        return (
            struct.pack("!HHHHHH", query_id, 0x8183, 1, 0, 0, 0)
            + packet[12:question_end]
        )
    answer = b""
    answer_count = 0
    if name == DUAL_STACK_NAME:
        encoded = ipaddress.IPv6Address(dual_stack_ipv6).packed
        answer = b"\xc0\x0c" + struct.pack("!HHIH", 28, 1, 30, len(encoded)) + encoded
        answer_count = 1
    return (
        struct.pack("!HHHHHH", query_id, 0x8180, 1, answer_count, 0, 0)
        + packet[12:question_end]
        + answer
    )


def serve_udp(port: int, dual_stack_ipv6: str) -> None:
    with socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as server:
        server.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        server.bind(("::", port))
        while True:
            packet, peer = server.recvfrom(2048)
            try:
                response = build_response(packet, dual_stack_ipv6=dual_stack_ipv6)
            except (UnicodeDecodeError, ValueError):
                continue
            server.sendto(response, peer)


def receive_exact(connection: socket.socket, size: int) -> bytes:
    payload = bytearray()
    while len(payload) < size:
        chunk = connection.recv(size - len(payload))
        if not chunk:
            raise ValueError("DNS-over-TCP request is truncated")
        payload.extend(chunk)
    return bytes(payload)


def build_tcp_response(frame: bytes, *, dual_stack_ipv6: str) -> bytes:
    if len(frame) < 2:
        raise ValueError("DNS-over-TCP frame is truncated")
    declared = struct.unpack("!H", frame[:2])[0]
    if declared == 0 or declared != len(frame) - 2:
        raise ValueError("DNS-over-TCP frame length is invalid")
    response = build_response(frame[2:], dual_stack_ipv6=dual_stack_ipv6)
    return struct.pack("!H", len(response)) + response


def serve_tcp(port: int, dual_stack_ipv6: str) -> None:
    with socket.socket(socket.AF_INET6, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        server.bind(("::", port))
        server.listen(16)
        while True:
            connection, _ = server.accept()
            with connection:
                try:
                    header = receive_exact(connection, 2)
                    declared = struct.unpack("!H", header)[0]
                    if not 1 <= declared <= 2048:
                        raise ValueError("DNS-over-TCP question length is invalid")
                    packet = receive_exact(connection, declared)
                    response = build_tcp_response(
                        header + packet,
                        dual_stack_ipv6=dual_stack_ipv6,
                    )
                except (UnicodeDecodeError, ValueError):
                    continue
                connection.sendall(response)


def serve(port: int, dual_stack_ipv6: str) -> None:
    ipaddress.IPv6Address(dual_stack_ipv6)
    udp = threading.Thread(
        target=serve_udp,
        args=(port, dual_stack_ipv6),
        daemon=True,
    )
    udp.start()
    serve_tcp(port, dual_stack_ipv6)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--dual-stack-ipv6", required=True)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    serve(args.port, args.dual_stack_ipv6)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
