"""rst-after-sni-match pattern classifier (v1, dry-run only).

Inspect outbound packets for a TLS ClientHello whose SNI matches a
configured blocklist. The classifier is pure: given a trace and a
config, it returns either "matched at packet N" or "no match".

v1 contract: traces must carry explicit `sni` / `tls_record_type` /
`direction` / `transport` fields per packet. v1 does not parse raw
ClientHello bytes — that responsibility belongs to the live-mode
nfqueue handler that follows this PR.
"""

from __future__ import annotations

from typing import Any


PATTERN_ID = "rst-after-sni-match"


def classify(trace: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    """Return a verdict result for one (desync trace, pattern config) cell.

    Output schema:
        {
            "pattern_id": "rst-after-sni-match",
            "matched": bool,
            "matched_packet_index": int | None,
            "matched_sni": str | None,
            "evidence": {...}
        }
    """
    blocklist = {host.lower() for host in config.get("sni_blocklist", [])}
    packets = trace.get("packets", [])
    for index, pkt in enumerate(packets):
        if pkt.get("direction") != "outbound":
            continue
        if pkt.get("transport") != "tcp":
            continue
        sni = pkt.get("sni")
        if not sni:
            continue
        if sni.lower() in blocklist:
            return {
                "pattern_id": PATTERN_ID,
                "matched": True,
                "matched_packet_index": index,
                "matched_sni": sni,
                "evidence": {
                    "tls_record_type": pkt.get("tls_record_type"),
                    "payload_bytes_len": len(bytes.fromhex(pkt.get("payload_hex") or "")),
                },
            }
    return {
        "pattern_id": PATTERN_ID,
        "matched": False,
        "matched_packet_index": None,
        "matched_sni": None,
        "evidence": {"packets_scanned": len(packets)},
    }
