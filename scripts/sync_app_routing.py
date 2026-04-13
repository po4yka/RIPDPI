#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib import parse, request


USER_AGENT = "RIPDPI app-routing sync"
DEFAULT_MANIFEST = "scripts/app-routing-sources.json"

ANDROID_PACKAGE_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$")


@dataclass(frozen=True)
class SourceSpec:
    name: str
    repo: str
    ref: str
    path: str
    raw_url: str


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def normalize_package_name(token: str) -> str | None:
    token = token.strip()
    if not token:
        return None
    if not ANDROID_PACKAGE_RE.match(token):
        return None
    return token


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


def build_package_list(manifest: dict[str, Any]) -> tuple[list[str], list[dict[str, Any]]]:
    all_packages: dict[str, None] = {}
    sources_meta: list[dict[str, Any]] = []

    for source_payload in manifest["sources"]:
        source = SourceSpec(
            name=source_payload["name"],
            repo=source_payload["repo"],
            ref=source_payload["ref"],
            path=source_payload["path"],
            raw_url=source_payload["rawUrl"],
        )
        raw_text = http_get_text(source.raw_url)
        raw_list = json.loads(raw_text)
        for entry in raw_list:
            token = entry if isinstance(entry, str) else str(entry)
            normalized = normalize_package_name(token)
            if normalized is not None:
                all_packages.setdefault(normalized, None)

        commit = fetch_latest_commit(source)
        sources_meta.append(
            {
                "name": source.name,
                "url": source.raw_url,
                "ref": source.ref,
                "commit": commit,
            }
        )

    return sorted(all_packages.keys()), sources_meta


def update_policy_file(
    policy: dict[str, Any],
    preset_id: str,
    packages: list[str],
    sources_meta: list[dict[str, Any]],
) -> dict[str, Any]:
    for preset in policy["presets"]:
        if preset["id"] == preset_id:
            preset["exactPackages"] = packages
            preset.setdefault("_syncSources", [])
            preset["_syncSources"] = sources_meta
            break
    return policy


def write_policy(
    policy: dict[str, Any],
    output_path: Path,
    check_only: bool,
) -> int:
    rendered = json.dumps(policy, indent=2, ensure_ascii=False) + "\n"
    if check_only:
        if not output_path.exists():
            return 1
        existing = json.loads(output_path.read_text(encoding="utf-8"))
        comparable = json.loads(rendered)
        existing.get("presets", [{}])[0].pop("_syncSources", None)
        comparable.get("presets", [{}])[0].pop("_syncSources", None)
        existing_packages = set(existing.get("presets", [{}])[0].get("exactPackages", []))
        comparable_packages = set(comparable.get("presets", [{}])[0].get("exactPackages", []))
        return 0 if existing_packages == comparable_packages else 1
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(rendered, encoding="utf-8")
    return 0


def load_manifest(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Sync bundled app routing package list from upstream.")
    parser.add_argument(
        "--manifest",
        default=DEFAULT_MANIFEST,
        help="Path to the checked-in app-routing manifest.",
    )
    parser.add_argument(
        "--output",
        default=None,
        help="Optional override for the policy file output path.",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Exit non-zero when the upstream package list differs from the checked-in policy.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    manifest_path = (repo_root() / args.manifest).resolve()
    manifest = load_manifest(manifest_path)
    output_relative = args.output or manifest["policyFile"]
    output_path = (repo_root() / output_relative).resolve()

    packages, sources_meta = build_package_list(manifest)

    policy = json.loads(output_path.read_text(encoding="utf-8"))
    policy = update_policy_file(policy, manifest["presetId"], packages, sources_meta)

    return write_policy(policy, output_path, check_only=args.check)


if __name__ == "__main__":
    raise SystemExit(main())
