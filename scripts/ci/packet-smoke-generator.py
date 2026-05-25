#!/usr/bin/env python3
"""Generate deterministic CLI packet-smoke cells from the desync axis space."""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
from pathlib import Path
from typing import Any

CLI_ARTIFACTS = [
    "fixture-manifest.json",
    "fixture-events.json",
    "cli-stderr.log",
    "test-output.txt",
    "capture.pcap",
    "capture.tshark.json",
]

AXES: dict[str, tuple[str, ...]] = {
    "split_offset": ("host+0", "host+1", "host+2", "host+3", "midsld", "midsld-1", "endhost-1", "endhost"),
    "tls_record_split": ("none", "sniext", "extlen", "auto_midsld"),
    "tlsrandrec_profile": ("off", "iana_firefox", "google_chrome", "bigsize_iana"),
    "udp_burst": ("off", "low", "medium", "high"),
    "quic_fake_profile": ("off", "compat_default", "realistic_initial"),
    "fake_ttl_ladder": ("off", "2", "4", "8", "16", "32"),
    "oob_byte_placement": ("off", "pre_handshake", "post_sni", "mid_app"),
}

DEFAULT_AXIS_VALUES = {
    "split_offset": "host+1",
    "tls_record_split": "none",
    "tlsrandrec_profile": "off",
    "udp_burst": "off",
    "quic_fake_profile": "off",
    "fake_ttl_ladder": "off",
    "oob_byte_placement": "off",
}


def stable_digest(seed: str, axis_values: dict[str, str]) -> str:
    payload = json.dumps(axis_values, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(f"{seed}\0{payload}".encode("utf-8")).hexdigest()


def traffic_kind_for(axis_values: dict[str, str]) -> str:
    if axis_values["udp_burst"] != "off" or axis_values["quic_fake_profile"] != "off":
        return "udp_quic"
    if (
        axis_values["tls_record_split"] != "none"
        or axis_values["tlsrandrec_profile"] != "off"
        or axis_values["fake_ttl_ladder"] != "off"
    ):
        return "tcp_tls"
    return "tcp_http"


def iter_axis_cells() -> list[dict[str, str]]:
    names = tuple(AXES.keys())
    values = (AXES[name] for name in names)
    return [dict(zip(names, combination, strict=True)) for combination in itertools.product(*values)]


def random_cells(seed: str, budget: int) -> list[dict[str, str]]:
    cells = iter_axis_cells()
    ranked = sorted(cells, key=lambda cell: stable_digest(seed, cell))
    return ranked[:budget]


def sweep_cells(seed: str, budget: int) -> list[dict[str, str]]:
    names = tuple(AXES.keys())
    cells: list[dict[str, str]] = []
    for left_index, left_name in enumerate(names):
        for right_name in names[left_index + 1 :]:
            for left_value in AXES[left_name]:
                for right_value in AXES[right_name]:
                    cell = dict(DEFAULT_AXIS_VALUES)
                    cell[left_name] = left_value
                    cell[right_name] = right_value
                    cells.append(cell)
    ranked = sorted(cells, key=lambda cell: stable_digest(f"sweep:{seed}", cell))
    return ranked[:budget]


def generated_scenario(seed: str, origin: str, index: int, axis_values: dict[str, str]) -> dict[str, Any]:
    digest = stable_digest(seed, axis_values)
    return {
        "id": f"cli_packet_smoke_generated_{origin}_{index:03d}_{digest[:10]}",
        "lane": "cli",
        "requiredCapability": "host_pcap",
        "testSelector": "cli_packet_smoke_generated_cell",
        "trafficKind": traffic_kind_for(axis_values),
        "generator_seed": seed,
        "generator_axis_values": axis_values,
        "generator_origin": origin,
        "artifacts": CLI_ARTIFACTS,
    }


def generate(seed: str, budget: int, origin: str) -> list[dict[str, Any]]:
    if budget < 0:
        raise ValueError("budget must be non-negative")
    if budget == 0:
        return []
    if origin == "random":
        cells = random_cells(seed, budget)
    elif origin == "sweep":
        cells = sweep_cells(seed, budget)
    else:
        raise ValueError(f"unsupported generator origin: {origin}")
    return [generated_scenario(seed, origin, index, axis_values) for index, axis_values in enumerate(cells)]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", required=True, help="Stable seed recorded in every generated fixture")
    parser.add_argument("--budget", type=int, required=True, help="Number of generated cells to emit")
    parser.add_argument("--origin", choices=("random", "sweep"), default="random")
    parser.add_argument("--registry", type=Path, help="Validate that the generated-cell test selector exists")
    return parser


def validate_registry(registry_path: Path) -> None:
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    selectors = {entry.get("testSelector") for entry in registry}
    if "cli_packet_smoke_generated_cell" not in selectors:
        raise SystemExit(f"{registry_path} does not register cli_packet_smoke_generated_cell")


def main() -> int:
    args = build_parser().parse_args()
    if args.registry is not None:
        validate_registry(args.registry)
    scenarios = generate(args.seed, args.budget, args.origin)
    print(json.dumps(scenarios, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
