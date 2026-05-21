#!/usr/bin/env python3
import os
import socket
import struct
import sys
import threading
import time


MAX_DNS_MESSAGE_SIZE = 4096
DEFAULT_TTL_SECONDS = 60


def env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or raw == "":
        return default
    value = float(raw)
    if value < 0:
        raise ValueError(f"{name} must be >= 0")
    return value


def env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw == "":
        return default
    value = int(raw)
    if value < 0:
        raise ValueError(f"{name} must be >= 0")
    return value


def daemonize(pid_file: str, log_file: str) -> None:
    pid_file = os.path.abspath(pid_file)
    log_file = os.path.abspath(log_file)

    first_pid = os.fork()
    if first_pid > 0:
        os._exit(0)

    os.setsid()
    second_pid = os.fork()
    if second_pid > 0:
        os._exit(0)

    os.chdir("/")
    os.umask(0o022)
    log_fd = os.open(log_file, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
    null_fd = os.open(os.devnull, os.O_RDONLY)
    os.dup2(null_fd, sys.stdin.fileno())
    os.dup2(log_fd, sys.stdout.fileno())
    os.dup2(log_fd, sys.stderr.fileno())
    os.close(null_fd)
    os.close(log_fd)
    with open(pid_file, "w", encoding="utf-8") as pid_output:
        pid_output.write(f"{os.getpid()}\n")


def parse_args() -> None:
    args = sys.argv[1:]
    if not args:
        return
    if len(args) != 5 or args[0] != "--daemonize" or args[1] != "--pid-file" or args[3] != "--log-file":
        raise SystemExit("usage: host-dns.py [--daemonize --pid-file PATH --log-file PATH]")
    daemonize(args[2], args[4])


def default_records() -> dict[str, str]:
    lab_ip = os.environ.get("RIPDPI_LAB_RECORD_IP", "127.0.0.1")
    return {
        "ok.test": lab_ip,
        "http.test": lab_ip,
        "tls.test": lab_ip,
        "tcp.test": lab_ip,
        "udp.test": lab_ip,
        "quic.test": lab_ip,
        "relay.test": lab_ip,
        "blocked.test": "203.0.113.10",
        "poisoned.test": "198.51.100.20",
    }


def parse_query(packet: bytes) -> tuple[bytes, bytes, str, int, int]:
    if len(packet) < 12:
        raise ValueError("DNS packet is shorter than header")
    offset = 12
    labels: list[str] = []
    while True:
        if offset >= len(packet):
            raise ValueError("DNS question ended before root label")
        label_length = packet[offset]
        offset += 1
        if label_length == 0:
            break
        if label_length & 0xC0:
            raise ValueError("compressed query names are not supported")
        end = offset + label_length
        if end > len(packet):
            raise ValueError("DNS label extends beyond packet")
        labels.append(packet[offset:end].decode("ascii").lower())
        offset = end
    if offset + 4 > len(packet):
        raise ValueError("DNS question is missing type or class")
    qtype, qclass = struct.unpack("!HH", packet[offset : offset + 4])
    question = packet[12 : offset + 4]
    return packet[:2], question, ".".join(labels), qtype, qclass


def build_response(packet: bytes, records: dict[str, str]) -> bytes:
    transaction_id, question, name, qtype, qclass = parse_query(packet)
    answers = b""
    flags = 0x8180
    answer_count = 0
    if qclass == 1 and qtype == 1 and name in records:
        answer_count = 1
        answers = (
            b"\xc0\x0c" +
            struct.pack("!HHIH", 1, 1, DEFAULT_TTL_SECONDS, 4) +
            socket.inet_aton(records[name])
        )
    elif name not in records:
        flags = 0x8183
    header = transaction_id + struct.pack("!HHHHH", flags, 1, answer_count, 0, 0)
    return header + question + answers


def serve_udp(sock: socket.socket, records: dict[str, str], delay_seconds: float, drop_every: int) -> None:
    received_count = 0
    while True:
        packet, source = sock.recvfrom(MAX_DNS_MESSAGE_SIZE)
        received_count += 1
        try:
            if drop_every and received_count % drop_every == 0:
                print(f"dns udp drop source={source[0]}:{source[1]} size={len(packet)} count={received_count}", flush=True)
                continue
            if delay_seconds:
                time.sleep(delay_seconds)
            response = build_response(packet, records)
            print(f"dns udp source={source[0]}:{source[1]} size={len(packet)} count={received_count}", flush=True)
            sock.sendto(response, source)
        except Exception as error:
            print(f"dns udp query failed from {source}: {error}", file=sys.stderr, flush=True)


def recv_exact(conn: socket.socket, byte_count: int) -> bytes:
    received = b""
    while len(received) < byte_count:
        chunk = conn.recv(byte_count - len(received))
        if not chunk:
            break
        received += chunk
    return received


def handle_tcp_client(conn: socket.socket, source: tuple[str, int], records: dict[str, str]) -> None:
    with conn:
        print(f"dns tcp accept source={source[0]}:{source[1]}", flush=True)
        length_prefix = recv_exact(conn, 2)
        if len(length_prefix) != 2:
            print(f"dns tcp short_prefix source={source[0]}:{source[1]} size={len(length_prefix)}", flush=True)
            return
        expected = struct.unpack("!H", length_prefix)[0]
        packet = recv_exact(conn, expected)
        if len(packet) != expected:
            print(
                f"dns tcp short_body source={source[0]}:{source[1]} "
                f"expected={expected} actual={len(packet)}",
                flush=True,
            )
            return
        response = build_response(packet, records)
        print(f"dns tcp source={source[0]}:{source[1]} size={len(packet)}", flush=True)
        conn.sendall(struct.pack("!H", len(response)) + response)


def serve_tcp(sock: socket.socket, records: dict[str, str]) -> None:
    while True:
        conn, source = sock.accept()
        thread = threading.Thread(target=handle_tcp_client, args=(conn, source, records), daemon=True)
        thread.start()


def main() -> int:
    parse_args()
    host = os.environ.get("RIPDPI_DNS_HOST", "0.0.0.0")
    port = int(os.environ.get("RIPDPI_DNS_PORT", "1053"))
    udp_delay_seconds = env_float("RIPDPI_DNS_UDP_DELAY_MS", 0.0) / 1000.0
    udp_drop_every = env_int("RIPDPI_DNS_UDP_DROP_EVERY", 0)
    records = default_records()
    udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    tcp_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        tcp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        udp_sock.bind((host, port))
        tcp_sock.bind((host, port))
        tcp_sock.listen(32)
        print(
            f"host dns listening on {host}:{port} udp_delay_ms={udp_delay_seconds * 1000:g} "
            f"udp_drop_every={udp_drop_every} records={records}",
            flush=True,
        )
        threading.Thread(target=serve_tcp, args=(tcp_sock, records), daemon=True).start()
        serve_udp(udp_sock, records, udp_delay_seconds, udp_drop_every)
    finally:
        udp_sock.close()
        tcp_sock.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(0)
    except Exception as error:
        print(f"host dns failed: {error}", file=sys.stderr, flush=True)
        raise
