"""ip-blackhole-after-n-bytes pattern classifier (v1, dry-run only).

Stateful per-flow classifier: accumulates payload bytes from outbound
packets toward a configured destination IP/port and matches once the
total exceeds `threshold_bytes`. In live mode, the adversary would drop
all subsequent packets to that destination. In dry-run we record the
packet index at which the threshold was crossed.

Config:

- `threshold_bytes` (int, required): bytes after which the blackhole
  engages.
- `target_dst_ports` (list[int], optional): if non-empty, only packets
  to these destination ports count. Defaults to all ports.

The desync mode evades this pattern by either (a) sending fewer than
`threshold_bytes` per flow, (b) splitting the flow across multiple
destinations / ports, or (c) staying entirely within a single packet
whose contents the adversary cannot react to in time.
"""

from __future__ import annotations

from typing import Any


PATTERN_ID = "ip-blackhole-after-n-bytes"


def classify(trace: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    threshold = int(config.get("threshold_bytes", 0))
    target_ports = set(config.get("target_dst_ports") or [])
    packets = trace.get("packets", [])
    cumulative = 0
    for index, pkt in enumerate(packets):
        if pkt.get("direction") != "outbound":
            continue
        if target_ports and pkt.get("dst_port") not in target_ports:
            continue
        size = len(bytes.fromhex(pkt.get("payload_hex") or ""))
        cumulative += size
        if cumulative >= threshold and threshold > 0:
            return {
                "pattern_id": PATTERN_ID,
                "matched": True,
                "matched_packet_index": index,
                "evidence": {
                    "cumulative_bytes": cumulative,
                    "threshold_bytes": threshold,
                    "dst_port": pkt.get("dst_port"),
                },
            }
    return {
        "pattern_id": PATTERN_ID,
        "matched": False,
        "matched_packet_index": None,
        "evidence": {
            "cumulative_bytes": cumulative,
            "threshold_bytes": threshold,
            "packets_scanned": len(packets),
        },
    }
