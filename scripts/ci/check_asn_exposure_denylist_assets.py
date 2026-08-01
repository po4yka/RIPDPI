#!/usr/bin/env python3
"""CI guard for the ASN exposure denylist advisory boundary.

The Android app may document server-side exposure reduction, but it must not
ship generated ASN/IP denylist data in runtime assets. This check scans tracked
``src/main/assets`` files and rejects deploy-style denylist artifacts while
allowing the existing local routing and diagnostics metadata assets.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]

ALLOWED_ASN_ASSET_PATHS = {
    Path("core/data/settings/src/main/assets/integrations/asn-routing-map.json"),
    Path("core/diagnostics/src/main/assets/dpich/ipv4_whitelist_asns.json"),
    # Hand-curated subnet/ASN reference for well-known public DoH providers,
    # loaded by DpiAssetLoader alongside the entry above. It is neither generated
    # nor deploy-style, and it predates this guard; it went unreported only
    # because the quoted-key ASN pattern below could never match.
    Path("core/diagnostics/src/main/assets/dpich/doh_bootstrap_subnet_metadata.json"),
}

PROVENANCE_URLS = (
    "https://github.com/C24Be/AS_Network_List",
    "https://docs.google.com/spreadsheets/d/1YWS5aMEykkM9koxcZW1q_bZBi2j1UGmTbhFhOfnrd4k",
)

FORBIDDEN_PATH_PATTERNS = (
    re.compile(r"(^|[-_/])asn[-_]?exposure($|[-_/.])", re.IGNORECASE),
    re.compile(r"(^|[-_/])as[-_]?network[-_]?list($|[-_/.])", re.IGNORECASE),
    re.compile(r"(^|[-_/])service[-_]?network[-_]?(deny|block|black)list($|[-_/.])", re.IGNORECASE),
    re.compile(r"(^|[-_/])asn[-_]?(deny|block|black)list($|[-_/.])", re.IGNORECASE),
    re.compile(r"(^|[-_/])ip[-_]?(deny|block|black)list($|[-_/.])", re.IGNORECASE),
)

# Prefix-length alternatives are ordered longest-first on purpose. Python picks
# the first alternative that matches, so listing `[0-9]` first would truncate
# `/24` to `/2` and report a prefix the asset never contained.
CIDR_RE = re.compile(
    r"(?<![\w.])(?:"
    r"(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}/(?:3[0-2]|[12]\d|[0-9])"
    r"|"
    r"(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}/(?:12[0-8]|1[01]\d|[1-9]\d|[0-9])"
    r")",
    re.IGNORECASE,
)
# No `\b` before the quoted key: a word boundary cannot exist between a quote
# and the `{`, `,`, or space that precedes it in JSON, so anchoring there made
# this alternative unreachable and let `"asn": 64500` through unnoticed. The
# value may be bare, quoted, or the head of an array.
ASN_RE = re.compile(
    r"\bAS[0-9]{2,}\b" r'|"asn"\s*:\s*\[?\s*"?[0-9]{2,}',
    re.IGNORECASE,
)


def iter_asset_files(repo_root: Path) -> list[Path]:
    """Return repo-relative runtime asset files under Android source sets."""
    asset_files: list[Path] = []
    for assets_dir in repo_root.glob("**/src/main/assets"):
        relative_dir = assets_dir.relative_to(repo_root)
        if any(part in {".git", ".gradle", "build"} for part in relative_dir.parts):
            continue
        for path in assets_dir.rglob("*"):
            if path.is_file():
                asset_files.append(path.relative_to(repo_root))
    return sorted(asset_files)


def _read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return ""


def validate_no_asn_exposure_denylist_assets(repo_root: Path = ROOT) -> list[str]:
    """Return violations for deploy-style ASN/IP denylist assets."""
    violations: list[str] = []

    for relative_path in iter_asset_files(repo_root):
        path_text = relative_path.as_posix()
        lower_path = path_text.lower()
        text = _read_text(repo_root / relative_path)

        for source_url in PROVENANCE_URLS:
            if source_url in text:
                violations.append(
                    f"{path_text}: runtime asset must not embed ASN exposure source provenance or feed content"
                )

        has_forbidden_name = any(pattern.search(lower_path) for pattern in FORBIDDEN_PATH_PATTERNS)
        if has_forbidden_name:
            violations.append(
                f"{path_text}: runtime asset name looks like a deploy-side ASN/IP exposure denylist artifact"
            )

        if relative_path in ALLOWED_ASN_ASSET_PATHS:
            continue

        cidr_count = len(CIDR_RE.findall(text))
        has_asn_data = ASN_RE.search(text) is not None
        if has_forbidden_name and (cidr_count > 0 or has_asn_data):
            violations.append(
                f"{path_text}: runtime asset combines denylist naming with ASN/CIDR data"
            )
        elif cidr_count > 0 and has_asn_data:
            violations.append(
                f"{path_text}: runtime asset combines ASN identifiers with IP ranges; keep exposure denylist fixtures deploy-side"
            )

    return violations


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=ROOT,
        help="Repository root to scan. Defaults to this script's repository.",
    )
    args = parser.parse_args(argv)

    violations = validate_no_asn_exposure_denylist_assets(args.repo_root.resolve())
    if violations:
        for violation in violations:
            print(violation, file=sys.stderr)
        return 1

    print("No ASN exposure denylist runtime assets found.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
