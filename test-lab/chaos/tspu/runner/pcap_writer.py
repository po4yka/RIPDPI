"""Stdlib-only pcap writer.

Emits libpcap-format files so the evidence artifacts produced by the
dry-run mode are inspectable in Wireshark/tshark without requiring
scapy or dpkt in CI. The writer produces one synthetic Ethernet frame
per packet in the trace, with IPv4 + TCP/UDP headers carrying the
payload hex from the trace.

The file format follows the classic libpcap header:

    magic (4) | version_major (2) | version_minor (2)
    thiszone (4) | sigfigs (4) | snaplen (4) | linktype (4)

Per-record:

    ts_sec (4) | ts_usec (4) | incl_len (4) | orig_len (4) | data...

Linktype is EN10MB (1). Synthetic addresses are documentation-range
(192.0.2.0/24 and 198.51.100.0/24, RFC 5737).
"""

from __future__ import annotations

import struct
from typing import Any, BinaryIO


PCAP_MAGIC = 0xA1B2C3D4
LINKTYPE_EN10MB = 1
SNAPLEN = 65535

SRC_IP = bytes([192, 0, 2, 1])
DST_IP = bytes([198, 51, 100, 1])
SRC_MAC = bytes.fromhex("020000000001")
DST_MAC = bytes.fromhex("020000000002")
ETHERTYPE_IPV4 = 0x0800

PROTO_TCP = 6
PROTO_UDP = 17


def _ipv4_checksum(header: bytes) -> int:
    total = 0
    if len(header) % 2 == 1:
        header += b"\x00"
    for i in range(0, len(header), 2):
        total += int.from_bytes(header[i : i + 2], "big")
    while total >> 16:
        total = (total & 0xFFFF) + (total >> 16)
    return (~total) & 0xFFFF


def _build_ipv4(payload: bytes, proto: int) -> bytes:
    total_len = 20 + len(payload)
    header = struct.pack(
        "!BBHHHBBH4s4s",
        0x45,
        0x00,
        total_len,
        0,
        0,
        64,
        proto,
        0,
        SRC_IP,
        DST_IP,
    )
    checksum = _ipv4_checksum(header)
    header = struct.pack(
        "!BBHHHBBH4s4s",
        0x45,
        0x00,
        total_len,
        0,
        0,
        64,
        proto,
        checksum,
        SRC_IP,
        DST_IP,
    )
    return header


def _build_tcp(payload: bytes, src_port: int, dst_port: int) -> bytes:
    return struct.pack(
        "!HHIIBBHHH",
        src_port,
        dst_port,
        0,
        0,
        0x50,
        0x18,
        0xFFFF,
        0,
        0,
    ) + payload


def _build_udp(payload: bytes, src_port: int, dst_port: int) -> bytes:
    udp_len = 8 + len(payload)
    return struct.pack("!HHHH", src_port, dst_port, udp_len, 0) + payload


def _build_ethernet(payload: bytes) -> bytes:
    return DST_MAC + SRC_MAC + struct.pack("!H", ETHERTYPE_IPV4) + payload


def _build_frame(pkt: dict[str, Any]) -> bytes:
    payload = bytes.fromhex(pkt.get("payload_hex") or "")
    src_port = int(pkt.get("src_port") or 12345)
    dst_port = int(pkt.get("dst_port") or 443)
    transport = pkt.get("transport")
    if transport == "tcp":
        l4 = _build_tcp(payload, src_port, dst_port)
        ip = _build_ipv4(l4, PROTO_TCP)
    elif transport == "udp":
        l4 = _build_udp(payload, src_port, dst_port)
        ip = _build_ipv4(l4, PROTO_UDP)
    else:
        l4 = payload
        ip = _build_ipv4(l4, PROTO_TCP)
    return _build_ethernet(ip + l4)


def write_pcap(stream: BinaryIO, packets: list[dict[str, Any]]) -> int:
    """Write a list of packet dicts to `stream` as libpcap. Returns count."""
    stream.write(
        struct.pack(
            "!IHHIIII",
            PCAP_MAGIC,
            2,
            4,
            0,
            0,
            SNAPLEN,
            LINKTYPE_EN10MB,
        )
    )
    for i, pkt in enumerate(packets):
        frame = _build_frame(pkt)
        stream.write(
            struct.pack(
                "!IIII",
                i + 1,
                0,
                len(frame),
                len(frame),
            )
        )
        stream.write(frame)
    return len(packets)
