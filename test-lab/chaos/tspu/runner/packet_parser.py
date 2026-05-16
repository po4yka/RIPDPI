"""Raw-bytes -> trace-dict parser used by the live nfqueue handler.

The dry-run path consumes already-annotated trace dicts (fixtures);
the live path receives raw IP/L4 payloads from nfqueue and has to
populate the same trace-dict shape on the fly. The parser is the
boundary that performs that translation.

Scope is intentionally narrow:

- TLS ClientHello SNI extraction from the start of a TCP payload.
- QUIC long-header detection and packet-type classification (Initial vs
  Handshake/0-RTT) from the first byte of a UDP payload.
- A combined `parse_outbound_packet` that produces the trace-packet
  dict that pattern classifiers expect.

It does NOT decrypt QUIC Initial CRYPTO frames (the live mode emits
sni/alpn = None for QUIC and the pattern still matches on the
long-header Initial bit + the runtime-supplied filter), and it does
NOT reassemble fragmented TLS records across packet boundaries — the
live handler operates per-datagram / per-segment to mirror what a
stateless TSPU would see.
"""

from __future__ import annotations

from typing import Any


def extract_tls_sni(payload: bytes) -> str | None:
    """Best-effort SNI extraction from a single TLS ClientHello segment.

    Returns None when the payload does not look like a TLS handshake
    record carrying a ClientHello whose SNI extension is fully contained
    in this segment. Returning None on split ClientHellos is the
    desired behaviour: a per-segment TSPU has no way to see it either.
    """
    if len(payload) < 5:
        return None
    if payload[0] != 0x16:  # handshake content type
        return None
    body = payload[5:]
    if len(body) < 4 or body[0] != 0x01:  # ClientHello handshake type
        return None
    ch = body[4:]
    if len(ch) < 34:
        return None
    cur = 34
    if cur >= len(ch):
        return None
    sid_len = ch[cur]
    cur += 1 + sid_len
    if cur + 2 > len(ch):
        return None
    cs_len = int.from_bytes(ch[cur : cur + 2], "big")
    cur += 2 + cs_len
    if cur + 1 > len(ch):
        return None
    cm_len = ch[cur]
    cur += 1 + cm_len
    if cur + 2 > len(ch):
        return None
    ext_total = int.from_bytes(ch[cur : cur + 2], "big")
    cur += 2
    ext_end = cur + ext_total
    if ext_end > len(ch):
        return None
    while cur + 4 <= ext_end:
        ext_type = int.from_bytes(ch[cur : cur + 2], "big")
        ext_len = int.from_bytes(ch[cur + 2 : cur + 4], "big")
        cur += 4
        if ext_type == 0x0000:  # server_name
            if cur + 5 > len(ch):
                return None
            name_type = ch[cur + 2]
            if name_type != 0x00:  # host_name
                return None
            name_len = int.from_bytes(ch[cur + 3 : cur + 5], "big")
            name_start = cur + 5
            if name_start + name_len > len(ch):
                return None
            try:
                return ch[name_start : name_start + name_len].decode("ascii")
            except UnicodeDecodeError:
                return None
        cur += ext_len
    return None


def parse_quic_long_header(payload: bytes) -> tuple[bool, str | None]:
    """Return (is_long_header, packet_type_str|None).

    Long-header packets have the high bit set and the fixed bit set
    (0xC0 mask). Packet type is bits 5-4: 00 Initial, 01 0-RTT, 10
    Handshake, 11 Retry.
    """
    if not payload:
        return False, None
    first = payload[0]
    if (first & 0xC0) != 0xC0:
        return False, None
    pt = (first >> 4) & 0x03
    mapping = {0: "initial", 1: "zero_rtt", 2: "handshake", 3: "retry"}
    return True, mapping.get(pt)


def parse_outbound_packet(
    *,
    transport: str,
    payload: bytes,
    src_port: int,
    dst_port: int,
) -> dict[str, Any]:
    """Build the trace-packet dict that pattern classifiers expect.

    `direction` is hard-coded to "outbound" because the live handler
    only attaches to outbound traffic.
    """
    pkt: dict[str, Any] = {
        "direction": "outbound",
        "transport": transport,
        "src_port": src_port,
        "dst_port": dst_port,
        "payload_hex": payload.hex(),
    }
    if transport == "tcp":
        sni = extract_tls_sni(payload)
        if sni is not None:
            pkt["sni"] = sni
            pkt["tls_record_type"] = "clienthello"
        else:
            pkt["sni"] = None
            pkt["tls_record_type"] = None
    elif transport == "udp":
        is_long, packet_type = parse_quic_long_header(payload)
        pkt["quic_long_header"] = is_long
        pkt["quic_packet_type"] = packet_type
        # SNI / ALPN inside QUIC Initial requires header-protection
        # removal + CRYPTO-frame decryption. Out of scope for v1.x; the
        # live handler emits None and the pattern still matches on the
        # long-header bit if a future runtime configuration enriches
        # these fields.
        pkt["sni"] = None
        pkt["alpn"] = None
    return pkt
