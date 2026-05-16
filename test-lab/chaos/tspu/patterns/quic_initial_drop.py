"""quic-initial-drop pattern classifier (v1, dry-run only).

Inspect outbound UDP datagrams for QUIC Initial packets matching a
configured SNI/ALPN combination. The classifier is pure: given a trace
and a config, it returns either "matched at packet N" or "no match".

v1 contract: traces must carry explicit `quic_long_header` /
`quic_packet_type` / `sni` / `alpn` / `direction` / `transport` fields
per packet. v1 does not parse raw QUIC long headers or attempt to
decrypt the Initial's CRYPTO frame — those are live-mode concerns.
"""

from __future__ import annotations

from typing import Any


PATTERN_ID = "quic-initial-drop"


def classify(trace: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    """Return a verdict result for one (desync trace, pattern config) cell.

    Output schema:
        {
            "pattern_id": "quic-initial-drop",
            "matched": bool,
            "matched_packet_index": int | None,
            "matched_sni": str | None,
            "matched_alpn": str | None,
            "evidence": {...}
        }
    """
    sni_blocklist = {host.lower() for host in config.get("sni_blocklist", [])}
    alpn_blocklist = {alpn.lower() for alpn in config.get("alpn_blocklist", [])}
    packets = trace.get("packets", [])
    for index, pkt in enumerate(packets):
        if pkt.get("direction") != "outbound":
            continue
        if pkt.get("transport") != "udp":
            continue
        if pkt.get("quic_long_header") is not True:
            continue
        if pkt.get("quic_packet_type") != "initial":
            continue
        sni = (pkt.get("sni") or "").lower() or None
        alpn = (pkt.get("alpn") or "").lower() or None
        sni_hit = sni is not None and sni in sni_blocklist
        alpn_hit = alpn is not None and alpn in alpn_blocklist
        if sni_hit or alpn_hit:
            return {
                "pattern_id": PATTERN_ID,
                "matched": True,
                "matched_packet_index": index,
                "matched_sni": sni,
                "matched_alpn": alpn,
                "evidence": {
                    "payload_bytes_len": len(bytes.fromhex(pkt.get("payload_hex") or "")),
                    "matched_on": [k for k, v in (("sni", sni_hit), ("alpn", alpn_hit)) if v],
                },
            }
    return {
        "pattern_id": PATTERN_ID,
        "matched": False,
        "matched_packet_index": None,
        "matched_sni": None,
        "matched_alpn": None,
        "evidence": {"packets_scanned": len(packets)},
    }
