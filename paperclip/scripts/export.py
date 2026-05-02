#!/usr/bin/env python3
"""Export the running Paperclip company's agents into the in-tree paperclip/ store.

Reads from:
  - http://127.0.0.1:3100  (Paperclip API; override with PAPERCLIP_URL)
  - ~/.paperclip/instances/default/companies/<cid>/agents/<aid>/instructions/AGENTS.md

Writes to:
  - paperclip/agents/<urlKey>/AGENTS.md
  - paperclip/agents/<urlKey>/agent.json   (sanitized, name-based reportsTo)
  - paperclip/manifest.json

Usage:  python3 paperclip/scripts/export.py
"""
import json
import os
import pathlib
import shutil
import sys
import urllib.request

API = os.environ.get("PAPERCLIP_URL", "http://127.0.0.1:3100")
CID = os.environ.get("PAPERCLIP_COMPANY_ID", "f2d1e11d-c7ec-4dc6-b969-ac766a6d925f")
HOME = pathlib.Path(os.environ.get("PAPERCLIP_HOME", os.path.expanduser("~/.paperclip/instances/default")))

ROOT = pathlib.Path(__file__).resolve().parent.parent
AGENTS_DIR = ROOT / "agents"

DROP_ADAPTER_KEYS = {"instructionsFilePath", "instructionsRootPath"}


def get(path):
    return json.loads(urllib.request.urlopen(f"{API}{path}").read())


def main():
    agents = get(f"/api/companies/{CID}/agents")
    ids_to_name = {a["id"]: a["name"] for a in agents}

    manifest = []
    for a in sorted(agents, key=lambda x: x["name"]):
        url_key = a["urlKey"]
        aid = a["id"]
        out_dir = AGENTS_DIR / url_key
        out_dir.mkdir(parents=True, exist_ok=True)

        fs = HOME / "companies" / CID / "agents" / aid / "instructions" / "AGENTS.md"
        if not fs.exists():
            sys.exit(f"missing materialized AGENTS.md for {a['name']} at {fs}")
        shutil.copy(fs, out_dir / "AGENTS.md")

        cfg = {k: v for k, v in (a.get("adapterConfig") or {}).items() if k not in DROP_ADAPTER_KEYS}
        rt = a.get("reportsTo")
        sanitized = {
            "name": a["name"],
            "role": a["role"],
            "title": a.get("title"),
            "reportsTo": ids_to_name.get(rt) if rt else None,
            "capabilities": a.get("capabilities"),
            "adapterType": a["adapterType"],
            "adapterConfig": cfg,
            "runtimeConfig": a.get("runtimeConfig"),
            "budgetMonthlyCents": a.get("budgetMonthlyCents", 0),
        }
        (out_dir / "agent.json").write_text(json.dumps(sanitized, indent=2, sort_keys=True) + "\n")
        manifest.append({
            "name": a["name"],
            "urlKey": url_key,
            "role": a["role"],
            "reportsTo": sanitized["reportsTo"],
            "adapter": a["adapterType"],
            "model": cfg.get("model"),
        })

    (ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=False) + "\n")
    print(f"exported {len(agents)} agents to {AGENTS_DIR}")


if __name__ == "__main__":
    main()
