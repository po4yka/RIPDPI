"""Linux nfqueue adapter.

Wraps `netfilterqueue` so `live.run_with_adapter` can dispatch packets
through the pattern matrix without importing kernel-specific modules
unconditionally. Importing this module fails on macOS/Windows or when
`python3-netfilterqueue` is not installed; callers must catch
`ImportError` and fall back to dry-run.

Packet parsing is intentionally minimal: this module only enough to
turn a kernel-supplied IP datagram into (transport, payload, src_port,
dst_port). It does NOT pull in scapy; the IP/TCP/UDP header parse is
done by hand to keep the live image small and the parser auditable.
"""

from __future__ import annotations

import socket
import struct
import threading
import time
from typing import Callable

from . import live as live_mod

from netfilterqueue import NetfilterQueue  # type: ignore


def _parse_ipv4(buf: bytes) -> tuple[int, bytes] | None:
    """Return (l4_proto, l4_bytes) from an IPv4 datagram, or None."""
    if len(buf) < 20:
        return None
    version_ihl = buf[0]
    if (version_ihl >> 4) != 4:
        return None
    ihl = (version_ihl & 0x0F) * 4
    if ihl < 20 or len(buf) < ihl:
        return None
    proto = buf[9]
    return proto, buf[ihl:]


def _parse_ports(transport: str, l4: bytes) -> tuple[int, int, bytes] | None:
    if transport == "tcp":
        if len(l4) < 20:
            return None
        src_port = struct.unpack("!H", l4[0:2])[0]
        dst_port = struct.unpack("!H", l4[2:4])[0]
        data_offset = ((l4[12] >> 4) & 0x0F) * 4
        if data_offset < 20 or len(l4) < data_offset:
            return None
        return src_port, dst_port, l4[data_offset:]
    if transport == "udp":
        if len(l4) < 8:
            return None
        src_port = struct.unpack("!H", l4[0:2])[0]
        dst_port = struct.unpack("!H", l4[2:4])[0]
        return src_port, dst_port, l4[8:]
    return None


class NfqueueAdapter:
    """Production kernel adapter.

    The constructor binds to `queue_num`; `consume` runs the
    netfilterqueue main loop until the kernel closes the queue or
    `shutdown()` is called from another thread / a signal handler.
    """

    def __init__(self, queue_num: int = 0):
        self._queue_num = queue_num
        self._nfq = NetfilterQueue()
        self._bound = False
        self._shutdown = threading.Event()

    def consume(self, handler: Callable[[str, bytes, int, int], "live_mod.Verdict"]) -> None:
        def _on_pkt(pkt):  # pragma: no cover - exercised in live mode only
            buf = pkt.get_payload()
            parsed = _parse_ipv4(buf)
            if parsed is None:
                pkt.accept()
                return
            proto, l4 = parsed
            if proto == socket.IPPROTO_TCP:
                transport = "tcp"
            elif proto == socket.IPPROTO_UDP:
                transport = "udp"
            else:
                pkt.accept()
                return
            ports = _parse_ports(transport, l4)
            if ports is None:
                pkt.accept()
                return
            src_port, dst_port, payload = ports
            verdict = handler(transport, payload, src_port, dst_port)
            if verdict.accept:
                pkt.accept()
            else:
                pkt.drop()

        self._nfq.bind(self._queue_num, _on_pkt)
        self._bound = True
        self._shutdown.clear()
        try:
            while not self._shutdown.is_set():
                self._nfq.run(block=False)
                time.sleep(0.05)
        finally:
            if self._bound:
                self._nfq.unbind()
                self._bound = False

    def shutdown(self) -> None:  # pragma: no cover - exercised in live mode only
        self._shutdown.set()
