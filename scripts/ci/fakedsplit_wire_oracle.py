#!/usr/bin/env python3
"""Verify complete raw fakedsplit sends against the test-owned byte contract."""
from __future__ import annotations

import argparse
import ipaddress
import json
from pathlib import Path
import struct
import subprocess
import sys

FIXTURE = Path(__file__).resolve().parents[2] / "app/src/androidTest/assets/fakedsplit-wire.json"
# The public RIPDPI order contract, independent of the Rust emission builder.
ORDERS = (("FA", "A", "FB", "B"), ("FA", "FB", "A", "B"),
          ("A", "FA", "B", "FB"), ("A", "B", "FA", "FB"))


def checksum_valid(data: bytes) -> bool:
    data += b"\0" * (len(data) % 2)
    total = sum(struct.unpack(f"!{len(data) // 2}H", data))
    while total >> 16:
        total = (total & 65535) + (total >> 16)
    return total == 65535


def decode_hex(value: str) -> bytes:
    if not isinstance(value, str) or len(value) % 2:
        raise ValueError("invalid source hex")
    try:
        return bytes.fromhex(value)
    except ValueError as error:
        raise ValueError("invalid source hex") from error


def parse_packet(frame: bytes, ip_offset: int) -> dict:
    """Read IP/TCP bytes; tshark only supplies the capture's link-layer offset."""
    ip = frame[ip_offset:]
    if not ip:
        raise ValueError("missing IP header")
    version = ip[0] >> 4
    if version == 4:
        ihl = (ip[0] & 15) * 4
        if len(ip) < 20 or ihl < 20 or len(ip) < ihl or ip[9] != 6:
            raise ValueError("invalid IPv4 TCP header")
        length = int.from_bytes(ip[2:4], "big")
        if length > len(ip) or length < ihl or int.from_bytes(ip[6:8], "big") & 0x3fff:
            raise ValueError("truncated or fragmented IPv4 packet")
        if not checksum_valid(ip[:ihl]):
            raise ValueError("bad IPv4 checksum")
        source, target, ttl = ip[12:16], ip[16:20], ip[8]
        tcp = ip[ihl:length]
        pseudo = source + target + bytes((0, 6)) + len(tcp).to_bytes(2, "big")
    elif version == 6:
        if len(ip) < 40 or ip[6] != 6:
            raise ValueError("expected IPv6 TCP without extensions")
        length = int.from_bytes(ip[4:6], "big")
        if length < 20 or len(ip) < 40 + length:
            raise ValueError("truncated IPv6 packet")
        source, target, ttl = ip[8:24], ip[24:40], ip[7]
        tcp = ip[40:40 + length]
        pseudo = source + target + len(tcp).to_bytes(4, "big") + b"\0\0\0\6"
    else:
        raise ValueError("unsupported IP version")
    if len(tcp) < 20:
        raise ValueError("truncated TCP header")
    size = (tcp[12] >> 4) * 4
    if size < 20 or size > len(tcp):
        raise ValueError("invalid TCP data offset")
    options, cursor = [], 20
    while cursor < size:
        kind = tcp[cursor]
        if kind == 0:
            if any(tcp[cursor: size]):
                raise ValueError("nonzero TCP option padding")
            break
        if kind == 1:
            cursor += 1
            continue
        if cursor + 2 > size or tcp[cursor + 1] < 2 or cursor + tcp[cursor + 1] > size:
            raise ValueError("malformed TCP option")
        if kind == 19 and tcp[cursor + 1] != 18:
            raise ValueError("malformed MD5 option")
        options.append(kind)
        cursor += tcp[cursor + 1]
    payload = tcp[size:]
    # Kernel SYN checksum offload is not evidence about crafted data checksums.
    if payload and not checksum_valid(pseudo + tcp):
        raise ValueError("bad TCP checksum")
    return {
        "flow": (str(ipaddress.ip_address(source)), str(ipaddress.ip_address(target)),
                 int.from_bytes(tcp[:2], "big"), int.from_bytes(tcp[2:4], "big")),
        "sequence": int.from_bytes(tcp[4:8], "big"), "flags": tcp[13],
        "ttl": ttl, "payload": payload, "md5": 19 in options,
    }


def packets_from_tshark(packets: list[dict]) -> list[dict]:
    result = []
    for packet in packets:
        layers = packet["_source"]["layers"]
        ip = layers.get("ip_raw", layers.get("ipv6_raw"))
        if not isinstance(ip, list) or len(ip) < 3 or not isinstance(ip[1], int):
            raise ValueError("missing unambiguous raw IP offset")
        raw = layers.get("frame_raw")
        if not isinstance(raw, list) or not isinstance(raw[0], str):
            raise ValueError("missing raw capture bytes")
        result.append(parse_packet(decode_hex(raw[0]), ip[1]))
    return result


def validate_packets(packets: list[dict], contract: dict, port: int) -> dict:
    if contract.get("schemaVersion") != 1 or not 1 <= port <= 65535:
        raise ValueError("invalid wire contract or port")
    cases = contract["cases"]
    if not isinstance(cases, list) or len(cases) != 8:
        raise ValueError("source must contain each of the eight modes exactly once")
    if any(type(case["order"]) is not int or not isinstance(case["sequenceMode"], str) for case in cases):
        raise ValueError("invalid source mode")
    modes = {(case["order"], case["sequenceMode"]) for case in cases}
    if modes != {(order, mode) for order in range(4) for mode in ("duplicate", "sequential")}:
        raise ValueError("source must contain each of the eight modes exactly once")
    if any(not isinstance(case["id"], str) or not case["id"] for case in cases):
        raise ValueError("source must contain nonempty case identifiers")
    if len({case["id"] for case in cases}) != 8:
        raise ValueError("source must contain unique case identifiers")
    originals = [decode_hex(case["originalHex"]) for case in cases]
    split = contract["splitOffset"]
    fake_a, fake_b = map(decode_hex, contract["fakeRegionsHex"])
    if not isinstance(split, int) or split <= 0 or len(fake_a) != split or not fake_b:
        raise ValueError("invalid split source regions")
    if len({original[:split] for original in originals}) != 8:
        raise ValueError("source must contain distinct first regions to identify each case")
    flows = {}
    for packet in packets:
        if packet["flow"][3] == port:
            flows.setdefault(packet["flow"], []).append(packet)
    matched = set()
    for case in contract["cases"]:
        original = decode_hex(case["originalHex"])
        if len(original) != len(fake_a) + len(fake_b) or case["order"] not in range(4):
            raise ValueError("source length/order disagrees with split")
        if case["sequenceMode"] not in ("duplicate", "sequential"):
            raise ValueError("invalid source sequence mode")
        real_a, real_b = original[:split], original[split:]
        candidates = [flow for flow, records in flows.items()
                      if any(record["payload"] == real_a for record in records)]
        if len(candidates) != 1 or candidates[0] in matched:
            raise ValueError(f"{case['id']}: missing or ambiguous original source")
        flow = candidates[0]
        matched.add(flow)
        records = flows[flow]
        syns = {record["sequence"] for record in records if record["flags"] & 2}
        if len(syns) != 1:
            raise ValueError(f"{case['id']}: missing or ambiguous SYN")
        base = (syns.pop() + 1) & 0xffffffff
        expected = {"A": (real_a, 0), "B": (real_b, split),
                    "FA": (fake_a, 0), "FB": (fake_b, split)}
        seen = []
        for record in records:
            if not record["payload"]:
                continue
            roles = [role for role, (payload, offset) in expected.items()
                     if record["payload"] == payload and
                     (record["sequence"] - base) & 0xffffffff == offset and
                     record["flags"] == 24 and
                     record["ttl"] == contract["fakeTtl" if role.startswith("F") else "realTtl"] and
                     record["md5"] == (contract["fakeMd5"] and role.startswith("F"))]
            if len(roles) != 1:
                raise ValueError(f"{case['id']}: bytes, range, flags, TTL or MD5 differ from source")
            role = roles[0]
            # A duplicate capture record can occur on the loopback/any interface.
            if not seen or role != seen[-1]:
                seen.append(role)
        if tuple(seen[:4]) != ORDERS[case["order"]] or any(role.startswith("F") for role in seen[4:]):
            raise ValueError(f"{case['id']}: incomplete or wrong fake/real order: {seen}")
    if any(flow not in matched and any(p["payload"] for p in records) for flow, records in flows.items()):
        raise ValueError("unmatched TCP payload flow")
    return {"verdict": "pass", "evidence": "deterministic-raw-wire", "cases": len(matched)}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pcap", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--fixture", type=Path, default=FIXTURE)
    args = parser.parse_args(argv)
    try:
        if args.pcap.stat().st_size > 64 * 1024 * 1024:
            raise ValueError("capture exceeds the bounded oracle input")
        output = subprocess.run(
            ["tshark", "-r", str(args.pcap), "-Y", f"tcp.dstport == {args.port}", "-T", "json", "-x"],
            check=True, capture_output=True, text=True, timeout=30,
        ).stdout
        result = validate_packets(packets_from_tshark(json.loads(output)), json.loads(args.fixture.read_text()), args.port)
    except (OSError, ValueError, KeyError, TypeError, IndexError, subprocess.SubprocessError) as error:
        print(json.dumps({"verdict": "fail", "reason": str(error)}))
        return 1
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
