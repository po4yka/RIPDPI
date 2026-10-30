#!/usr/bin/env python3
"""Check or bump the pinned upstream NaiveProxy client release.

--check compares sources.json with the latest upstream release and exits 2
when the pin is behind. Without --check the script pins a release (the latest
one, or --tag): it downloads each per-ABI plugin APK, verifies it against the
GitHub release asset digest, records the APK and libnaive.so sha256 values in
sources.json and updates the Kotlin version check. A bump needs the wire
comparison and the android-naiveproxy acceptance scenario again; the script
prints those steps.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import sys
import tempfile
import urllib.request
from pathlib import Path
from typing import Callable

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = REPO_ROOT / "native/naiveproxy-upstream/sources.json"
DEFAULT_KOTLIN = REPO_ROOT / "core/service/src/main/kotlin/com/poyka/ripdpi/services/NaiveUpstreamLauncher.kt"
API = "https://api.github.com"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
TAG_PATTERN = re.compile(r"^v(\d+\.\d+\.\d+\.\d+)-(\d+)$")
KOTLIN_PREFIX_PATTERN = re.compile(r'(internal const val NaiveUpstreamVersionPrefix = )"naive [^"]*"')

_spec = importlib.util.spec_from_file_location("fetch_naive_upstream", Path(__file__).with_name("fetch-naive-upstream.py"))
assert _spec is not None and _spec.loader is not None
fetch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fetch)

FetchJson = Callable[[str], object]


class BumpError(Exception):
    pass


def github_json(path: str) -> object:
    request = urllib.request.Request(f"{API}{path}", headers={"Accept": "application/vnd.github+json"})
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def version_prefix(tag: str) -> str:
    """The first `naive --version` token pair of a release, for example `naive 154.0.8037.49`."""
    match = TAG_PATTERN.match(tag)
    if not match:
        raise BumpError(f"unexpected upstream tag format: {tag}")
    return f"naive {match.group(1)}"


def tag_key(tag: str) -> tuple[int, ...]:
    match = TAG_PATTERN.match(tag)
    if not match:
        raise BumpError(f"unexpected upstream tag format: {tag}")
    return (*map(int, match.group(1).split(".")), int(match.group(2)))


def tag_commit(repo: str, tag: str, get: FetchJson) -> str:
    target = get(f"/repos/{repo}/git/ref/tags/{tag}")["object"]  # type: ignore[index]
    if target["type"] == "tag":  # An annotated tag points at a tag object.
        target = get(f"/repos/{repo}/git/tags/{target['sha']}")["object"]  # type: ignore[index]
    if target["type"] != "commit":
        raise BumpError(f"{tag} does not resolve to a commit")
    return target["sha"]


def build_manifest(repo: str, release: dict, commit: str, download: Callable[[str, Path], None], workdir: Path) -> dict:
    tag = release["tag_name"]
    assets = {asset["name"]: asset for asset in release["assets"]}
    entries = {}
    for abi in ABIS:
        name = f"naiveproxy-plugin-{tag}-{abi}.apk"
        asset = assets.get(name)
        if asset is None:
            raise BumpError(f"{tag} has no asset {name}")
        digest = asset.get("digest") or ""
        if not digest.startswith("sha256:"):
            raise BumpError(f"{name} has no sha256 digest in the release metadata")
        apk = workdir / name
        download(asset["browser_download_url"], apk)
        apk_sha = fetch.sha256_file(apk)
        if apk_sha != digest.removeprefix("sha256:"):
            raise BumpError(f"{name}: downloaded sha256 {apk_sha} differs from the release digest {digest}")
        elf = workdir / f"{abi}-libnaive.so"
        fetch.extract_member(apk, abi, elf)
        entries[abi] = {
            "asset": name,
            "url": asset["browser_download_url"],
            "apkSha256": apk_sha,
            "libnaiveSha256": fetch.sha256_file(elf),
        }
    return {"repo": repo, "tag": tag, "commit": commit, "abis": entries}


def write_manifest(path: Path, pinned: dict) -> None:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    manifest.update(tag=pinned["tag"], commit=pinned["commit"], abis=pinned["abis"])
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def write_kotlin_prefix(path: Path, tag: str) -> None:
    source = path.read_text(encoding="utf-8")
    updated, count = KOTLIN_PREFIX_PATTERN.subn(rf'\1"{version_prefix(tag)}"', source)
    if count != 1:
        raise BumpError(f"{path.name} has no single NaiveUpstreamVersionPrefix constant")
    path.write_text(updated, encoding="utf-8")


def main(argv: list[str] | None = None, get: FetchJson = github_json) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="exit 2 when a newer upstream release exists")
    parser.add_argument("--tag", help="release tag to pin (default: the latest release)")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--kotlin-source", type=Path, default=DEFAULT_KOTLIN)
    args = parser.parse_args(argv)

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    repo, pinned = manifest["repo"], manifest["tag"]
    try:
        release = get(f"/repos/{repo}/releases/tags/{args.tag}" if args.tag else f"/repos/{repo}/releases/latest")
        tag = release["tag_name"]  # type: ignore[index]
        if args.check:
            if tag_key(tag) > tag_key(pinned):
                print(f"NaiveProxy upstream {tag} is newer than the pinned {pinned}; run scripts/ci/bump-naive-upstream.py")
                return 2
            print(f"NaiveProxy upstream pin {pinned} is current (latest {tag})")
            return 0
        if tag == pinned:
            print(f"{pinned} is already pinned")
            return 0
        with tempfile.TemporaryDirectory(prefix="naive-bump-") as tmp:
            new_pin = build_manifest(repo, release, tag_commit(repo, tag, get), fetch.download, Path(tmp))  # type: ignore[arg-type]
        write_manifest(args.manifest, new_pin)
        write_kotlin_prefix(args.kotlin_source, tag)
    except (BumpError, fetch.FetchError, OSError, KeyError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(f"Pinned {tag} (was {pinned}). Next steps before you commit:")
    print("  1. python3 scripts/ci/fetch-naive-upstream.py")
    print("  2. ./gradlew :core:service:testDebugUnitTest")
    print("  3. the wire comparison and the android-naiveproxy acceptance scenario with --capture-tls;")
    print("     record the new JA4 in the change evidence")
    return 0


if __name__ == "__main__":
    sys.exit(main())
