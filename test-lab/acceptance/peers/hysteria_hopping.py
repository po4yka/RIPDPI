"""UDP port redirects for a real Hysteria peer, with physical path receipts."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import selectors
import socket
import threading
import time

import hysteria


class Redirects:
    """Keep each client path on a separate upstream UDP socket (NAT semantics)."""

    def __init__(self, address, ports=None):
        self.address = address
        self.family = socket.AF_INET6 if ':' in address[0] else socket.AF_INET
        self.selector = selectors.DefaultSelector()
        self.sockets = []
        self.paths = {}
        self.receipts = []
        self.errors = []
        self.stopped = threading.Event()
        try:
            for port in ports if ports is not None else [0, 0, 0]:
                front = socket.socket(self.family, socket.SOCK_DGRAM)
                self.sockets.append(front)
                front.bind((address[0], port))
                front.setblocking(False)
                self.selector.register(front, selectors.EVENT_READ, ('front', front))
        except Exception:
            for sock in self.sockets:
                sock.close()
            self.selector.close()
            raise
        self.ports = [sock.getsockname()[1] for sock in self.sockets]
        self.worker = threading.Thread(target=self.run, daemon=True)
        self.worker.start()

    def run(self):
        try:
            while not self.stopped.is_set():
                for key, _ in self.selector.select(timeout=0.1):
                    kind, metadata = key.data
                    data, source = key.fileobj.recvfrom(65535)
                    if kind == 'front':
                        front = metadata
                        identity = (front.getsockname()[1], source)
                        upstream = self.paths.get(identity)
                        if upstream is None:
                            if len(self.paths) >= 64:
                                raise RuntimeError('redirect path bound exceeded')
                            upstream = socket.socket(self.family, socket.SOCK_DGRAM)
                            upstream.connect(self.address)
                            upstream.setblocking(False)
                            self.paths[identity] = upstream
                            self.sockets.append(upstream)
                            self.selector.register(upstream, selectors.EVENT_READ, ('back', (front, source)))
                            self.receipts.append({'remote_port': identity[0], 'local_port': source[1], 'time': time.monotonic()})
                        upstream.send(data)
                    else:
                        front, client = metadata
                        front.sendto(data, client)
        except OSError as error:
            if not self.stopped.is_set():
                self.errors.append(str(error))
        except Exception as error:
            self.errors.append(str(error))

    def close(self):
        self.stopped.set()
        self.worker.join(timeout=2)
        for sock in self.sockets:
            sock.close()
        self.selector.close()
        if self.worker.is_alive():
            raise RuntimeError('UDP redirect worker did not stop')


def fixture_input(case):
    directory = os.environ.get('RIPDPI_HYSTERIA_WIRE_FIXTURE_DIR')
    if directory is None:
        return None, None, {'kind': 'native-specimen'}
    if not directory:
        raise ValueError('Kotlin fixture directory must not be empty')
    mode = 'random' if case['randomized'] else 'fixed'
    path = Path(directory) / f'peer-flat-{mode}.json'
    raw = path.read_bytes()
    if len(raw) > 1_048_576:
        raise ValueError('Kotlin fixture is too large')
    wire = json.loads(raw)
    if not isinstance(wire, dict) or wire.get('schemaVersion') != 10 or wire.get('kind') != 'hysteria2':
        raise ValueError('Kotlin fixture has the wrong native relay shape')
    policy = wire.get('hysteriaPortHopPorts')
    parts = policy.split(',') if isinstance(policy, str) else []
    if len(parts) != 3 or any(not part.isascii() or not part.isdecimal() for part in parts):
        raise ValueError('Kotlin peer fixture must contain three scalar hopping ports')
    ports = [int(part) for part in parts]
    if ports != sorted(set(ports)) or not all(1 <= port <= 65535 for port in ports):
        raise ValueError('Kotlin peer fixture ports are not canonical')
    maximum = 5500 if case['randomized'] else None
    if wire.get('hysteriaPortHopIntervalMillis') != 5000 or wire.get('hysteriaPortHopIntervalMaxMillis') != maximum:
        raise ValueError('Kotlin peer fixture cadence does not match the case')
    return path, ports, {'kind': 'kotlin-serializer', 'sha256': hashlib.sha256(raw).hexdigest()}


@contextmanager
def peer(cache, out_dir, run_id, cleanup, case):
    fixture, ports, wire_source = fixture_input(case)
    with hysteria.peer(cache, out_dir, run_id, cleanup, ipv6=case['ipv6'], salamander=case['salamander'], persistent=True, payload_bytes=8192) as upstream:
        redirects = Redirects(upstream['server_address'], ports)
        try:
            env = dict(upstream['env'])
            if fixture is not None:
                env['RIPDPI_HYSTERIA_WIRE_FIXTURE'] = str(fixture.resolve())
            host = upstream['server_address'][0]
            port = redirects.ports[0]
            env['RIPDPI_HYSTERIA_ENDPOINT'] = f'[{host}]:{port}' if case['ipv6'] else f'{host}:{port}'
            env['RIPDPI_HYSTERIA_HOP_PORTS'] = ','.join(str(port) for port in sorted(redirects.ports))
            env['RIPDPI_HYSTERIA_HOP_MAX_MILLIS'] = '5500' if case['randomized'] else '5000'
            yield dict(upstream, env=env, redirects=redirects, wire_source=wire_source)
        finally:
            redirects.close()
