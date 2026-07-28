#!/usr/bin/env python3
"""Relay bounded ordinary-action markers from adb reverse to the fixture PCAP."""

from __future__ import annotations

import argparse
import ipaddress
import re
import socket
from collections.abc import Sequence


MAX_MARKER_BYTES = 160
MARKER_RE = re.compile(
    rb"RIPDPI-ORDINARY-V1:[a-z0-9-]+:[0-9a-f]{64}:(?:action|outcome)"
)


def validated_marker(raw: bytes) -> bytes:
    marker = raw.rstrip(b"\r\n")
    if len(marker) > MAX_MARKER_BYTES or MARKER_RE.fullmatch(marker) is None:
        raise ValueError("invalid ordinary marker")
    return marker


def handle_connection(
    connection: socket.socket,
    *,
    destination: tuple[str, int],
) -> None:
    connection.settimeout(5)
    raw = connection.makefile("rb", buffering=0).readline(MAX_MARKER_BYTES + 2)
    try:
        marker = validated_marker(raw)
    except ValueError:
        try:
            connection.sendall(b"ERROR\n")
        except OSError:
            pass
        return
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as datagram:
        datagram.sendto(marker, destination)
    connection.sendall(b"OK\n")


def serve(*, listen_port: int, destination: tuple[str, int]) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", listen_port))
        listener.listen(8)
        while True:
            connection, _ = listener.accept()
            with connection:
                try:
                    handle_connection(connection, destination=destination)
                except OSError:
                    continue


def port(value: str) -> int:
    parsed = int(value)
    if not 1 <= parsed <= 65535:
        raise argparse.ArgumentTypeError("port must be in 1..65535")
    return parsed


def ipv4(value: str) -> str:
    parsed = ipaddress.ip_address(value)
    if parsed.version != 4 or parsed.is_unspecified or parsed.is_multicast:
        raise argparse.ArgumentTypeError("destination must be a unicast IPv4 address")
    return str(parsed)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-port", required=True, type=port)
    parser.add_argument("--destination-host", required=True, type=ipv4)
    parser.add_argument("--destination-port", required=True, type=port)
    args = parser.parse_args(argv)
    serve(
        listen_port=args.listen_port,
        destination=(args.destination_host, args.destination_port),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
