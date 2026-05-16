"""mtu-clamp pattern classifier (v1, dry-run only).

Match any outbound packet whose payload exceeds the configured
`mtu_payload_bytes` threshold. In live mode the adversary would either
fragment the packet (breaking PMTU discovery contracts that the flow
relies on) or drop it entirely. In dry-run we record the first
oversize packet.

Config:

- `mtu_payload_bytes` (int, required): payload-size threshold in bytes
  above which a packet matches.

The desync mode evades this pattern by keeping every outbound packet's
payload under the threshold. Many TLS fragments and small UDP datagrams
trivially pass; the pattern catches monolithic ClientHello / large
TLS record / large QUIC Initial shapes.
"""

from __future__ import annotations

from typing import Any


PATTERN_ID = "mtu-clamp"


def classify(trace: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    threshold = int(config.get("mtu_payload_bytes", 0))
    packets = trace.get("packets", [])
    for index, pkt in enumerate(packets):
        if pkt.get("direction") != "outbound":
            continue
        size = len(bytes.fromhex(pkt.get("payload_hex") or ""))
        if threshold > 0 and size > threshold:
            return {
                "pattern_id": PATTERN_ID,
                "matched": True,
                "matched_packet_index": index,
                "evidence": {
                    "payload_bytes_len": size,
                    "mtu_payload_bytes": threshold,
                    "transport": pkt.get("transport"),
                },
            }
    return {
        "pattern_id": PATTERN_ID,
        "matched": False,
        "matched_packet_index": None,
        "evidence": {
            "mtu_payload_bytes": threshold,
            "packets_scanned": len(packets),
        },
    }
