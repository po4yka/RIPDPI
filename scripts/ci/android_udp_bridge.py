"""Test-only USB datagram carrier; this does not prove a direct UDP network path."""

from __future__ import annotations

import hmac
import secrets
import select
import socket
import struct
import threading
import time


MAX_DATAGRAM = 65507
MAX_FLOWS = 64
FRAME_VERSION = 1
FRAME_HEADER = struct.Struct("!BIH")
MAX_LIFETIME_SECONDS = 600


def receive_exact(connection, size, stopped, deadline):
    result = bytearray()
    while len(result) < size and not stopped.is_set():
        if time.monotonic() >= deadline:
            raise TimeoutError("USB bridge connection deadline exceeded")
        try:
            chunk = connection.recv(size - len(result))
        except socket.timeout:
            continue
        if not chunk:
            if result:
                raise ValueError("truncated USB bridge frame")
            return None
        result.extend(chunk)
    return bytes(result) if len(result) == size else None


def send_exact(connection, packet, stopped, deadline):
    offset = 0
    while offset < len(packet) and not stopped.is_set():
        if time.monotonic() >= deadline:
            raise TimeoutError("USB bridge reply deadline exceeded")
        try:
            sent = connection.send(packet[offset:])
        except socket.timeout:
            continue
        if not sent:
            raise OSError("USB bridge connection closed during reply")
        offset += sent
    return offset == len(packet)


class UdpBridge:
    """Carry bounded datagrams to one exact loopback peer over an owned TCP port."""

    def __init__(self, udp_port: int, host: str = "127.0.0.1"):
        if (
            host != "127.0.0.1"
            or type(udp_port) is not int
            or not 1 <= udp_port <= 65535
        ):
            raise ValueError("USB bridge requires an exact loopback UDP peer")
        self.upstream = (host, udp_port)
        self.token = secrets.token_hex(32)
        self.port = 0
        self._listener = None
        self._connection = None
        self._thread = None
        self._started = False
        self._expires_at = None
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._error = None
        self._counts = {
            "scope": "usb-datagram",
            "connections": 0,
            "flows": 0,
            "unauthorized": 0,
            "datagramsToPeer": 0,
            "datagramsToDevice": 0,
            "bytesToPeer": 0,
            "bytesToDevice": 0,
            "peerRefused": 0,
        }

    def receipts(self) -> dict:
        with self._lock:
            return dict(self._counts)

    def start(self) -> None:
        if self._started:
            raise RuntimeError("USB bridge cannot restart")
        self._started = True
        self._expires_at = time.monotonic() + MAX_LIFETIME_SECONDS
        self._listener = socket.socket()
        try:
            self._listener.bind(("127.0.0.1", 0))
            self._listener.listen(1)
            self._listener.settimeout(0.2)
            self.port = self._listener.getsockname()[1]
            self._thread = threading.Thread(
                target=self._serve, name="owned-usb-datagrams", daemon=True
            )
            self._thread.start()
        except BaseException:
            self.close()
            raise

    def _serve(self):
        try:
            while not self._stop.is_set():
                if time.monotonic() >= self._expires_at:
                    raise TimeoutError("USB bridge lifetime exceeded")
                try:
                    connection, _ = self._listener.accept()
                except socket.timeout:
                    continue
                with connection:
                    try:
                        with self._lock:
                            self._connection = connection
                        connection.settimeout(0.2)
                        supplied = receive_exact(
                            connection,
                            32,
                            self._stop,
                            min(self._expires_at, time.monotonic() + 5),
                        )
                        if supplied is None:
                            continue
                        if not hmac.compare_digest(supplied, bytes.fromhex(self.token)):
                            with self._lock:
                                self._counts["unauthorized"] += 1
                            continue
                        with self._lock:
                            self._counts["connections"] += 1
                        self._exchange(connection)
                    except Exception as error:
                        # Save failure before EOF can trigger owner cleanup.
                        if not self._stop.is_set():
                            self._error = error
                        return
        except Exception as error:
            if not self._stop.is_set():
                self._error = error
        finally:
            with self._lock:
                self._connection = None

    def _exchange(self, connection):
        finished = threading.Event()
        errors = []
        deadline = self._expires_at
        peers = {}
        peers_lock = threading.Lock()

        def replies():
            try:
                while not finished.is_set() and not self._stop.is_set():
                    with peers_lock:
                        sources = {peer: flow_id for flow_id, peer in peers.items()}
                    if not sources:
                        finished.wait(0.05)
                        continue
                    readable, _, _ = select.select(list(sources), [], [], 0.2)
                    for peer in readable:
                        try:
                            packet = peer.recv(MAX_DATAGRAM + 1)
                        except socket.timeout:
                            continue
                        except ConnectionRefusedError:
                            # Injected peer loss does not stop the USB carrier.
                            with self._lock:
                                self._counts["peerRefused"] += 1
                            continue
                        if not 0 < len(packet) <= MAX_DATAGRAM:
                            raise ValueError("invalid UDP reply length")
                        frame = (
                            FRAME_HEADER.pack(FRAME_VERSION, sources[peer], len(packet))
                            + packet
                        )
                        if not send_exact(
                            connection,
                            frame,
                            finished,
                            min(deadline, time.monotonic() + 3),
                        ):
                            return
                        with self._lock:
                            self._counts["datagramsToDevice"] += 1
                            self._counts["bytesToDevice"] += len(packet)
            except Exception as error:
                if not finished.is_set() and not self._stop.is_set():
                    errors.append(error)
                    finished.set()

        response = threading.Thread(
            target=replies, name="owned-usb-replies", daemon=True
        )
        response.start()
        try:
            while not finished.is_set() and not self._stop.is_set():
                header = receive_exact(
                    connection, FRAME_HEADER.size, finished, deadline
                )
                if header is None:
                    break
                version, flow_id, size = FRAME_HEADER.unpack(header)
                if version != FRAME_VERSION or not 1 <= flow_id <= MAX_FLOWS:
                    raise ValueError("invalid USB datagram version or flow ID")
                if not 0 < size <= MAX_DATAGRAM:
                    raise ValueError("invalid USB datagram length")
                packet = receive_exact(connection, size, finished, deadline)
                if packet is None:
                    raise ValueError("truncated USB datagram")
                with peers_lock:
                    peer = peers.get(flow_id)
                    if peer is None:
                        peer = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                        try:
                            peer.connect(self.upstream)
                            peer.settimeout(0.2)
                        except BaseException:
                            peer.close()
                            raise
                        peers[flow_id] = peer
                        with self._lock:
                            self._counts["flows"] += 1
                try:
                    sent = peer.send(packet)
                except ConnectionRefusedError:
                    with self._lock:
                        self._counts["peerRefused"] += 1
                    continue
                if sent != len(packet):
                    raise OSError("partial UDP datagram send")
                with self._lock:
                    self._counts["datagramsToPeer"] += 1
                    self._counts["bytesToPeer"] += len(packet)
        finally:
            finished.set()
            response.join(timeout=4)
            for peer in peers.values():
                peer.close()
            if response.is_alive():
                raise RuntimeError("USB reply worker did not stop")
        if errors:
            raise errors[0]

    def close(self) -> None:
        self._stop.set()
        with self._lock:
            connection = self._connection
        if connection is not None:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        if self._listener is not None:
            self._listener.close()
        if self._thread is not None:
            self._thread.join(timeout=5)
            if self._thread.is_alive():
                raise RuntimeError("USB bridge did not stop")
        error, self._error = self._error, None
        if error is not None:
            raise RuntimeError("USB bridge transport failed") from error
