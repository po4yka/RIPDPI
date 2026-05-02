#!/usr/bin/env python3
"""Apply the in-tree paperclip/ store to a running Paperclip instance.

For each agent.json in paperclip/agents/<urlKey>/:
  - if no agent with that name exists, POST create it (resolving reportsTo by name)
  - else PATCH update the metadata
  - PUT the AGENTS.md bundle file
Topologically orders by reportsTo so referents exist before referrers.

Usage:  python3 paperclip/scripts/apply.py [--dry-run]
"""
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

API = os.environ.get("PAPERCLIP_URL", "http://127.0.0.1:3100")
CID = os.environ.get("PAPERCLIP_COMPANY_ID", "f2d1e11d-c7ec-4dc6-b969-ac766a6d925f")

ROOT = pathlib.Path(__file__).resolve().parent.parent
AGENTS_DIR = ROOT / "agents"
DRY = "--dry-run" in sys.argv


def http(method, path, body=None):
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(f"{API}{path}", data=data, method=method, headers=headers)
    try:
        return json.loads(urllib.request.urlopen(req).read())
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path} -> {e.code}: {e.read().decode()}")


def topo_sort(agents):
    by_name = {a["name"]: a for a in agents}
    ordered, seen = [], set()

    def visit(name):
        if name in seen:
            return
        a = by_name[name]
        rt = a.get("reportsTo")
        if rt and rt in by_name:
            visit(rt)
        seen.add(name)
        ordered.append(a)

    for a in agents:
        visit(a["name"])
    return ordered


def main():
    agent_files = sorted(AGENTS_DIR.glob("*/agent.json"))
    agents = [json.loads(f.read_text()) for f in agent_files]
    bundles = {a["name"]: (f.parent / "AGENTS.md").read_text() for a, f in zip(agents, agent_files)}

    live = http("GET", f"/api/companies/{CID}/agents")
    name_to_id = {a["name"]: a["id"] for a in live}

    for a in topo_sort(agents):
        name = a["name"]
        rt_name = a.get("reportsTo")
        rt_id = name_to_id.get(rt_name) if rt_name else None
        payload = {
            "name": name,
            "role": a["role"],
            "title": a.get("title"),
            "reportsTo": rt_id,
            "capabilities": a.get("capabilities"),
            "adapterType": a["adapterType"],
            "adapterConfig": a["adapterConfig"],
            "runtimeConfig": a.get("runtimeConfig"),
            "budgetMonthlyCents": a.get("budgetMonthlyCents", 0),
        }
        existing_id = name_to_id.get(name)
        if existing_id:
            print(f"PATCH {name} ({existing_id})")
            if not DRY:
                http("PATCH", f"/api/agents/{existing_id}", payload)
        else:
            print(f"POST  {name}")
            if not DRY:
                created = http("POST", f"/api/companies/{CID}/agents", payload)
                existing_id = created["id"]
                name_to_id[name] = existing_id

        if not DRY:
            http("PUT", f"/api/agents/{existing_id}/instructions-bundle/file",
                 {"path": "AGENTS.md", "content": bundles[name]})
        print(f"  bundle ({len(bundles[name])} bytes) {'(dry-run)' if DRY else 'uploaded'}")

    print(f"\napplied {len(agents)} agents")


if __name__ == "__main__":
    main()
