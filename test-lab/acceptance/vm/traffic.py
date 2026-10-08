#!/usr/bin/env python3
"""Local socket endpoints and probes. This does not emulate a VPN protocol."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import socket
import socketserver
import threading
import time


class TCP(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(5)
        try:
            while data := self.request.recv(65536):
                self.server.record(data)
                self.request.sendall(data)
        except (TimeoutError, ConnectionError):
            pass


class UDP(socketserver.BaseRequestHandler):
    def handle(self):
        data, sock = self.request
        self.server.record(data)
        sock.sendto(data, self.client_address)


def serve(ipv4: str, ipv6: str, control: str, directory: Path):
    directory.mkdir(parents=True, exist_ok=True)
    lock = threading.Lock()

    def record(data):
        with lock, (directory / 'receipts.jsonl').open('a') as output:
            # All traffic is generated test material; no network/device identifiers.
            output.write(json.dumps({'payload': data.decode('ascii'), 'bytes': len(data)}) + '\n')

    servers = []
    endpoints = {}
    for label, addr, family in [('ipv4', ipv4, socket.AF_INET), ('ipv6', ipv6, socket.AF_INET6),
                                ('control', control, socket.AF_INET)]:
        for protocol, base, handler in [('tcp', socketserver.ThreadingTCPServer, TCP),
                                        ('udp', socketserver.ThreadingUDPServer, UDP)]:
            cls = type('Endpoint', (base,), {'address_family': family, 'daemon_threads': True})
            server = cls((addr, 0), handler)
            server.record = record
            threading.Thread(target=server.serve_forever, daemon=True).start()
            endpoints[label+'_'+protocol] = server.server_address[1]
            servers.append(server)
    (directory / 'ready.tmp').write_text(json.dumps(endpoints))
    (directory / 'ready.tmp').replace(directory / 'ready.json')
    threading.Event().wait()


def probe(host: str, port: int, protocol: str, payload: str, count: int = 1, timeout: float = 1.0) -> dict:
    family = socket.AF_INET6 if ':' in host else socket.AF_INET
    received, timings = 0, []
    for index in range(count):
        message = (payload + (f'-{index:03d}' if count > 1 else '')).encode('ascii')
        started = time.monotonic()
        try:
            with socket.socket(family, socket.SOCK_STREAM if protocol == 'tcp' else socket.SOCK_DGRAM) as sock:
                sock.settimeout(timeout)
                sock.connect((host, port))
                sock.sendall(message)
                reply = sock.recv(65536)
                if reply == message:
                    received += 1
                    timings.append(time.monotonic() - started)
        except (TimeoutError, ConnectionError, OSError):
            pass
    return {'sent': count, 'received': received, 'elapsed_seconds': timings}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['serve', 'probe'])
    parser.add_argument('--host')
    parser.add_argument('--ipv6')
    parser.add_argument('--control')
    parser.add_argument('--out-dir', type=Path)
    parser.add_argument('--port', type=int)
    parser.add_argument('--protocol', choices=['tcp', 'udp'])
    parser.add_argument('--payload', default='acceptance')
    parser.add_argument('--count', type=int, default=1)
    parser.add_argument('--timeout', type=float, default=1.0)
    args = parser.parse_args()
    if args.action == 'serve':
        serve(args.host, args.ipv6, args.control, args.out_dir)
    else:
        print(json.dumps(probe(args.host, args.port, args.protocol, args.payload, args.count, args.timeout)))


if __name__ == '__main__':
    main()
