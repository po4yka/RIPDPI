#!/usr/bin/env python3
"""Send one fixed malformed test packet through an owned Linux AWG interface.

The runner starts this helper inside its private namespace. Open AF_PACKET first,
then drop all root IDs before accepting one bounded request over the owned pipe.
"""
import argparse
import hashlib
import json
import os
import socket
import struct
import time
from pathlib import Path


def malformed_packet(port):
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("invalid target port")
    payload = (b"DATA:malformed-ip:" + b"x" * 256)[:256]
    udp = struct.pack("!HHHH", 41002, port, 8 + len(payload), 0) + payload
    # The actual size is 284. A zero IPv4 total length is the intended defect.
    ip = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 0, 1, 0, 64, 17, 0,
                     socket.inet_aton("10.77.0.1"), socket.inet_aton("10.77.0.2"))
    total = sum(struct.unpack("!10H", ip))
    while total >> 16: total = (total & 65535) + (total >> 16)
    ip = ip[:10] + struct.pack("!H", (~total) & 65535) + ip[12:]
    return ip + udp


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--socket", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--uid", type=int, required=True)
    parser.add_argument("--gid", type=int, required=True)
    args = parser.parse_args()
    if os.geteuid() != 0 or args.uid <= 0 or args.gid <= 0:
        raise SystemExit("helper needs a non-root destination identity")
    with socket.socket(socket.AF_PACKET, socket.SOCK_DGRAM, socket.htons(0x0800)) as raw:
        raw.bind(("awg31", 0))
        os.setgroups([])
        os.setgid(args.gid)
        os.setuid(args.uid)
        os.umask(0o077)
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
            listener.bind(str(args.socket))
            listener.listen(1)
            listener.settimeout(30)
            try:
                print(json.dumps({"ready": True}), flush=True)
                connection, _ = listener.accept()
                with connection:
                    connection.settimeout(5)
                    request = connection.makefile("rb").readline(128)
                    value = json.loads(request)
                    if set(value) != {"port"}: raise ValueError("invalid raw request")
                    packet = malformed_packet(value["port"])
                    if raw.send(packet) != len(packet): raise ValueError("short raw send")
                    receipt = {"ip_length": len(packet), "declared_ip_length": 0,
                               "sha256": hashlib.sha256(packet).hexdigest(), "at_unix_ns": time.time_ns(),
                               "destination_port": value["port"]}
                    args.receipt.write_text(json.dumps(receipt) + "\n")
                    connection.sendall(b'{"sent":true}\n')
            finally:
                args.socket.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
