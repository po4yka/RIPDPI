#!/usr/bin/env python3
"""Diff in-tree paperclip/ store against the running Paperclip instance.

Exits 0 on no drift, 1 on drift, 2 on connection failure.
Comparison ignores per-machine fields Paperclip materializes at create-time
(`instructionsFilePath`, `instructionsRootPath`).

Usage:  python3 paperclip/scripts/sync-check.py
"""
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

API = os.environ.get("PAPERCLIP_URL", "http://127.0.0.1:3100")
CID = os.environ.get("PAPERCLIP_COMPANY_ID", "f2d1e11d-c7ec-4dc6-b969-ac766a6d925f")
HOME = pathlib.Path(os.environ.get("PAPERCLIP_HOME", os.path.expanduser("~/.paperclip/instances/default")))

ROOT = pathlib.Path(__file__).resolve().parent.parent
AGENTS_DIR = ROOT / "agents"
DROP_ADAPTER_KEYS = {"instructionsFilePath", "instructionsRootPath"}

# adapterConfig values can come back wrapped as {"type": "plain", "value": ...} for env vars.
# Normalize both sides before comparing.
def normalize(v):
    if isinstance(v, dict):
        if set(v.keys()) == {"type", "value"} and v.get("type") == "plain":
            return v["value"]
        return {k: normalize(x) for k, x in sorted(v.items())}
    if isinstance(v, list):
        return [normalize(x) for x in v]
    return v


def main():
    try:
        with urllib.request.urlopen(f"{API}/api/companies/{CID}/agents") as r:
            live = json.loads(r.read())
    except urllib.error.URLError as e:
        print(f"sync-check: cannot reach Paperclip at {API}: {e}", file=sys.stderr)
        sys.exit(2)

    live_by_name = {a["name"]: a for a in live}
    drift = []

    for agent_json in sorted(AGENTS_DIR.glob("*/agent.json")):
        url_key = agent_json.parent.name
        spec = json.loads(agent_json.read_text())
        name = spec["name"]
        bundle_tree = (agent_json.parent / "AGENTS.md").read_text()

        if name not in live_by_name:
            drift.append(f"MISSING in Paperclip: {name} (urlKey {url_key})")
            continue
        live_a = live_by_name[name]

        # Metadata diff
        rt_live_name = next((a["name"] for a in live if a["id"] == live_a.get("reportsTo")), None)
        live_cfg = {k: v for k, v in (live_a.get("adapterConfig") or {}).items() if k not in DROP_ADAPTER_KEYS}
        live_summary = {
            "role": live_a["role"],
            "title": live_a.get("title"),
            "reportsTo": rt_live_name,
            "capabilities": live_a.get("capabilities"),
            "adapterType": live_a["adapterType"],
            "adapterConfig": normalize(live_cfg),
            "runtimeConfig": normalize(live_a.get("runtimeConfig")),
            "budgetMonthlyCents": live_a.get("budgetMonthlyCents", 0),
        }
        tree_summary = {
            "role": spec["role"],
            "title": spec.get("title"),
            "reportsTo": spec.get("reportsTo"),
            "capabilities": spec.get("capabilities"),
            "adapterType": spec["adapterType"],
            "adapterConfig": normalize(spec["adapterConfig"]),
            "runtimeConfig": normalize(spec.get("runtimeConfig")),
            "budgetMonthlyCents": spec.get("budgetMonthlyCents", 0),
        }
        if live_summary != tree_summary:
            for k in tree_summary:
                if tree_summary[k] != live_summary[k]:
                    drift.append(f"METADATA drift {name}.{k}: tree={tree_summary[k]!r} live={live_summary[k]!r}")

        # Bundle diff (read live from disk; faster than API)
        live_bundle_path = HOME / "companies" / CID / "agents" / live_a["id"] / "instructions" / "AGENTS.md"
        if not live_bundle_path.exists():
            drift.append(f"BUNDLE missing on disk for {name}: {live_bundle_path}")
        else:
            live_bundle = live_bundle_path.read_text()
            if bundle_tree != live_bundle:
                drift.append(f"BUNDLE drift for {name} (tree={len(bundle_tree)}B, live={len(live_bundle)}B)")

    # Agents present in Paperclip but not tracked in tree
    tracked_names = {json.loads(f.read_text())["name"] for f in AGENTS_DIR.glob("*/agent.json")}
    for a in live:
        if a["name"] not in tracked_names:
            drift.append(f"UNTRACKED in tree: {a['name']} (id {a['id']})")

    if drift:
        print(f"sync-check: {len(drift)} drift item(s):")
        for d in drift:
            print(f"  - {d}")
        sys.exit(1)

    print(f"sync-check: clean ({len(tracked_names)} agents in sync)")


if __name__ == "__main__":
    main()
