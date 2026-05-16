"""sni-replace pattern classifier (v1, dry-run only).

Inspect outbound TLS ClientHello packets for a SNI on the blocklist. If
matched, the live-mode adversary would rewrite the ClientHello so the
handshake terminates at a sinkhole. In dry-run we record the matched
packet index — the verdict is `blocked` from the desync mode's
perspective because the original flow is redirected.

The classifier shape mirrors rst-after-sni-match: SNI presence in any
outbound TCP packet's explicit `sni` field constitutes a match. Both
patterns share the same v1 dry-run contract; they differ in live-mode
semantics (RST vs. ClientHello rewrite) and in what evidence a future
classifier-extension might surface.
"""

from __future__ import annotations

from typing import Any


PATTERN_ID = "sni-replace"


def classify(trace: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    blocklist = {host.lower() for host in config.get("sni_blocklist", [])}
    replacement = config.get("replacement_sni")
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
                    "would_replace_with": replacement,
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
