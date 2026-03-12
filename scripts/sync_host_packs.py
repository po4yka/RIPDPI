#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
from collections import OrderedDict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib import parse, request


USER_AGENT = "RIPDPI host-pack sync"
DEFAULT_MANIFEST = "scripts/host-pack-sources.json"


@dataclass(frozen=True)
class SourceSpec:
    name: str
    repo: str
    ref: str
    path: str
    raw_url: str


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def normalize_host_token(token: str) -> str | None:
    token = token.strip()
    if not token:
        return None

    normalized_chars: list[str] = []
    for char in token:
        if "A" <= char <= "Z":
            normalized_chars.append(char.lower())
        elif "-" <= char <= "9" or "a" <= char <= "z":
            normalized_chars.append(char)
        else:
            return None
    return "".join(normalized_chars) or None


def iter_meaningful_tokens(payload: str) -> list[str]:
    tokens: list[str] = []
    for raw_line in payload.splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if not line:
            continue
        tokens.extend(line.split())
    return tokens


def normalize_source_payload(payload: str) -> list[str]:
    hosts: "OrderedDict[str, None]" = OrderedDict()
    for token in iter_meaningful_tokens(payload):
        candidate = token
        if ":" in token:
            prefix, value = token.split(":", 1)
            if prefix not in {"domain", "full"}:
                continue
            candidate = value
        normalized = normalize_host_token(candidate)
        if normalized is not None:
            hosts.setdefault(normalized, None)
    return list(hosts.keys())


def http_get_text(url: str) -> str:
    req = request.Request(url, headers={"User-Agent": USER_AGENT})
    with request.urlopen(req, timeout=30) as response:
        return response.read().decode("utf-8")


def http_get_json(url: str) -> Any:
    return json.loads(http_get_text(url))


def fetch_latest_commit(source: SourceSpec) -> str:
    query = parse.urlencode(
        {
            "path": source.path,
            "sha": source.ref,
            "per_page": "1",
        },
    )
    commits_url = f"https://api.github.com/repos/{source.repo}/commits?{query}"
    payload = http_get_json(commits_url)
    if not payload:
        raise RuntimeError(f"No commits found for {source.repo}:{source.path}@{source.ref}")
    return str(payload[0]["sha"])


def build_catalog(manifest: dict[str, Any]) -> dict[str, Any]:
    generated_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    packs: list[dict[str, Any]] = []
    for pack in manifest["packs"]:
        source_payload = pack["source"]
        source = SourceSpec(
            name=source_payload["name"],
            repo=source_payload["repo"],
            ref=source_payload["ref"],
            path=source_payload["path"],
            raw_url=source_payload["rawUrl"],
        )
        raw_text = http_get_text(source.raw_url)
        hosts = normalize_source_payload(raw_text)
        commit = fetch_latest_commit(source)
        packs.append(
            {
                "id": pack["id"],
                "title": pack["title"],
                "description": pack["description"],
                "hostCount": len(hosts),
                "hosts": hosts,
                "sources": [
                    {
                        "name": source.name,
                        "url": source.raw_url,
                        "ref": source.ref,
                        "commit": commit,
                    }
                ],
            }
        )
    return {
        "version": manifest["version"],
        "generatedAt": generated_at,
        "packs": packs,
    }


def load_manifest(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_catalog(
    catalog: dict[str, Any],
    output_path: Path,
    check_only: bool,
) -> int:
    rendered = json.dumps(catalog, indent=2, ensure_ascii=True) + "\n"
    if check_only:
        if not output_path.exists():
            return 1
        existing_payload = json.loads(output_path.read_text(encoding="utf-8"))
        comparable_catalog = dict(catalog)
        comparable_catalog["generatedAt"] = existing_payload.get("generatedAt", "")
        return 0 if existing_payload == comparable_catalog else 1
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(rendered, encoding="utf-8")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Fetch and rebuild bundled host-pack presets.")
    parser.add_argument(
        "--manifest",
        default=DEFAULT_MANIFEST,
        help="Path to the checked-in host-pack manifest.",
    )
    parser.add_argument(
        "--output",
        default=None,
        help="Optional override for the generated catalog output path.",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Exit non-zero when the generated output differs from the checked-in catalog.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    manifest_path = (repo_root() / args.manifest).resolve()
    manifest = load_manifest(manifest_path)
    output_relative = args.output or manifest["output"]
    output_path = (repo_root() / output_relative).resolve()
    catalog = build_catalog(manifest)
    return write_catalog(catalog, output_path, check_only=args.check)


if __name__ == "__main__":
    raise SystemExit(main())
