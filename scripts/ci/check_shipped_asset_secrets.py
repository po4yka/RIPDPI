#!/usr/bin/env python3
"""CI guard: no credential material in shipped Android assets.

Scans every file under every shipped ``src/<source-set>/assets`` directory
(``main``, flavors, build types), tracked or not, so an ignored operator file
such as ``app/src/main/assets/preset.json`` is covered too. Test source sets do
not ship and are skipped. Detection is by content, never by file name: PEM
private-key blocks; any non-empty value under a private-key, preshared-key, PSK,
password, token, secret, UUID, auth, or obfs key; and a VLESS/VMess user ``id``.
An asset that is not UTF-8 text cannot be scanned and fails. Violations name the
file and key path, never the value.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import REPO_ROOT as ROOT

PEM_PRIVATE_KEY_RE = re.compile(r"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----")

# Key names are compared after lowercasing and dropping `_`, `-`, and `.`.
SECRET_KEY_SUFFIXES = ("privatekey", "presharedkey", "password", "passwd", "passphrase", "token", "secret")
SECRET_KEYS_EXACT = {"psk", "uuid", "auth", "authstr", "obfs"}
# An `id` is a credential only as the user id of a VLESS or VMess outbound; other ids are names.
USER_ID_PROTOCOLS = {"vless", "vmess"}
BARE_INTEGER_RE = re.compile(r"\d+|0[xX][0-9a-fA-F]+")
# Gradle test source sets (`test`, `androidTest`, `testFixtures`, and their variant names such as `testGithubDebug`).
TEST_SOURCE_SET_RE = re.compile(r"(?:test|androidTest|testFixtures)(?:[A-Z][A-Za-z0-9]*)?")

# `key = value` / `key: value` / `"key": "value"` outside JSON (WireGuard INI, YAML, Lua, ...).
TEXT_ASSIGNMENT_RE = re.compile(
    r"""["']?(?P<key>[A-Za-z][A-Za-z0-9_.\-]*)["']?\s*[:=]\s*"""
    r"""(?:"(?P<dq>[^"\n]*)"|'(?P<sq>[^'\n]*)'|(?P<bare>[^\s,;}\]#]+))"""
)


def is_secret_key(name: str) -> bool:
    normalized = re.sub(r"[_\-.]", "", name).lower()
    return normalized in SECRET_KEYS_EXACT or normalized.endswith(SECRET_KEY_SUFFIXES)


def is_test_source_set(name: str) -> bool:
    return TEST_SOURCE_SET_RE.fullmatch(name) is not None


def iter_asset_files(repo_root: Path) -> list[Path]:
    """Return repo-relative files under shipped Android asset directories."""
    asset_files: list[Path] = []
    for assets_dir in repo_root.glob("**/src/*/assets"):
        relative_dir = assets_dir.relative_to(repo_root)
        # Hidden directories hold nested checkouts (`.worktrees/*`) and tool state.
        if any(part.startswith(".") or part == "build" for part in relative_dir.parts):
            continue
        if is_test_source_set(relative_dir.parts[-2]):
            continue
        for path in assets_dir.rglob("*"):
            if path.is_file():
                asset_files.append(path.relative_to(repo_root))
    return sorted(asset_files)


def _is_secret_value(value: object) -> bool:
    if isinstance(value, bool) or value is None:
        return False
    if isinstance(value, str):
        return value.strip() != ""
    return isinstance(value, (int, float))


def _declares_user_id_protocol(node: dict) -> bool:
    return any(str(node.get(field, "")).lower() in USER_ID_PROTOCOLS for field in ("type", "protocol"))


def _json_secret_paths(node: object, path: str = "$", in_user_id_outbound: bool = False) -> list[str]:
    found: list[str] = []
    if isinstance(node, dict):
        in_user_id_outbound = in_user_id_outbound or _declares_user_id_protocol(node)
        for key, value in node.items():
            child = f"{path}.{key}"
            user_id = in_user_id_outbound and str(key).lower() == "id"
            if (is_secret_key(str(key)) or user_id) and _is_secret_value(value):
                found.append(child)
            found.extend(_json_secret_paths(value, child, in_user_id_outbound))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            found.extend(_json_secret_paths(value, f"{path}[{index}]", in_user_id_outbound))
    return found


def _text_secret_keys(text: str) -> list[str]:
    found: list[str] = []
    for match in TEXT_ASSIGNMENT_RE.finditer(text):
        if not is_secret_key(match.group("key")):
            continue
        bare = match.group("bare")
        # shortcut: a bare decimal or hex integer in code is an ID constant (Lua `TLS_EXT_PRE_SHARED_KEY = 41`,
        # `..._STATELESS_RESET_TOKEN=0x02`), so such an unquoted password in an INI/YAML asset passes; quote-aware
        # parsing per format would close it.
        if bare is not None and BARE_INTEGER_RE.fullmatch(bare):
            continue
        value = next((g for g in (match.group("dq"), match.group("sq"), bare) if g is not None), "")
        if value.strip() and value not in {"null", "nil", "None", "~"}:
            found.append(match.group("key"))
    return found


def secret_findings(text: str) -> list[str]:
    """Return key paths (never values) that carry secret material in ``text``."""
    findings = ["PEM private key block"] if PEM_PRIVATE_KEY_RE.search(text) else []
    try:
        document = json.loads(text)
    except ValueError:
        return findings + _text_secret_keys(text)
    return findings + _json_secret_paths(document)


def validate_no_shipped_asset_secrets(repo_root: Path = ROOT) -> list[str]:
    violations: list[str] = []
    for relative_path in iter_asset_files(repo_root):
        try:
            text = (repo_root / relative_path).read_text(encoding="utf-8")
        except UnicodeDecodeError:
            violations.append(f"{relative_path.as_posix()}: shipped asset is not UTF-8 text and cannot be scanned")
            continue
        for finding in secret_findings(text):
            violations.append(f"{relative_path.as_posix()}: shipped asset carries secret material at {finding}")
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

    violations = validate_no_shipped_asset_secrets(args.repo_root.resolve())
    if violations:
        for violation in violations:
            print(violation, file=sys.stderr)
        print(
            "Ship endpoint metadata and a bootstrap token slot only; credentials arrive by /bootstrap provisioning.",
            file=sys.stderr,
        )
        return 1

    print("No secret material found in shipped assets.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
