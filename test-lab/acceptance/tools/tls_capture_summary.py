"""Capture TLS handshakes to the owned NaiveProxy server and summarize JA4 and HelloRetryRequest.

capture: run tshark on a host interface until terminated (needs BPF access, so it is optional).
summarize: read a pcap and print one JSON object with, per TCP connection, the ClientHello JA4,
the ClientHello count and whether the server answered with a HelloRetryRequest.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys

# RFC 8446 section 4.1.3: a ServerHello with this random is a HelloRetryRequest.
HRR_RANDOM = "cf21ad74e59a6111be1d8c021e65b891c2a211167abb8c5e079e09e2c8a8339c"
FIELDS = (
    "tcp.stream",
    "frame.time_epoch",
    "tls.handshake.type",
    "tls.handshake.ja4",
    "tls.handshake.random",
    "tls.handshake.extensions_key_share_group",
)
DISPLAY_FILTER = "tls.handshake.type==1 || tls.handshake.type==2"


def capture_command(interface: str, port: int, pcap: str) -> list[str]:
    return ["tshark", "-i", interface, "-f", f"tcp port {port}", "-w", pcap]


def read_command(pcap: str) -> list[str]:
    cmd = ["tshark", "-r", pcap, "-Y", DISPLAY_FILTER, "-T", "fields", "-E", "separator=/t"]
    for field in FIELDS:
        cmd += ["-e", field]
    return cmd


def summarize_rows(text: str) -> dict:
    """Parse tab-separated tshark rows (one per packet) into per-connection results."""
    streams: dict[str, dict] = {}
    for line in text.splitlines():
        cells = line.split("\t")
        if len(cells) < len(FIELDS):
            cells += [""] * (len(FIELDS) - len(cells))
        stream, when, types, ja4, randoms, groups = cells[: len(FIELDS)]
        entry = streams.setdefault(
            stream,
            {"stream": int(stream), "first_seen": float(when), "client_hellos": 0, "ja4": None,
             "hello_retry_request": False, "key_share_groups": []},
        )
        kinds = [item for item in types.split(",") if item]
        entry["client_hellos"] += kinds.count("1")
        if ja4 and entry["ja4"] is None:
            entry["ja4"] = ja4.split(",")[0]
        if "1" in kinds and groups and not entry["key_share_groups"]:
            entry["key_share_groups"] = groups.split(",")
        for random in randoms.split(","):
            if random.replace(":", "").lower() == HRR_RANDOM:
                entry["hello_retry_request"] = True
    connections = sorted(streams.values(), key=lambda item: item["first_seen"])
    for item in connections:
        # A second ClientHello on one connection is the client's answer to a HelloRetryRequest.
        item["hello_retry_request"] = item["hello_retry_request"] or item["client_hellos"] > 1
    return {
        "connections": connections,
        "ja4": sorted({item["ja4"] for item in connections if item["ja4"]}),
        "hello_retry_request": any(item["hello_retry_request"] for item in connections),
    }


def summarize_pcap(pcap: str) -> dict:
    output = subprocess.run(read_command(pcap), check=True, capture_output=True, text=True, timeout=120).stdout
    return summarize_rows(output)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    cap = sub.add_parser("capture")
    cap.add_argument("--interface", default="lo0")
    cap.add_argument("--port", type=int, required=True)
    cap.add_argument("--pcap", required=True)
    summary = sub.add_parser("summarize")
    summary.add_argument("pcap")
    args = parser.parse_args(argv)
    if args.mode == "capture":
        return subprocess.call(capture_command(args.interface, args.port, args.pcap))
    print(json.dumps(summarize_pcap(args.pcap), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
