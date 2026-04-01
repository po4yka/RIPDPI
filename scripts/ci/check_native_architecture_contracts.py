#!/usr/bin/env python3

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

from check_native_hotspot_budgets import production_source


REPO_ROOT = Path(__file__).resolve().parents[2]
ADAPTER_FILES = (
    Path("native/rust/crates/ripdpi-android/src/proxy.rs"),
    Path("native/rust/crates/ripdpi-android/src/diagnostics.rs"),
    Path("native/rust/crates/ripdpi-tunnel-android/src/session.rs"),
)
CONFIG_ROOT = Path("native/rust/crates/ripdpi-config/src")
CONFIG_PARSE_ROOT = CONFIG_ROOT / "parse"
CONFIG_MODEL_PATH = CONFIG_ROOT / "model" / "mod.rs"
PARSE_OWNED_FN_PREFIXES = ("parse_", "normalize_")
PARSE_OWNED_FN_NAMES = {"data_from_str", "file_or_inline_bytes"}

TOP_LEVEL_FN_RE = re.compile(
    r"^\s*(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?fn\s+([A-Za-z_][A-Za-z0-9_]*)\b",
    re.MULTILINE,
)
TOP_LEVEL_FORBIDDEN_ITEM_PATTERNS = {
    "static": re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?static\b", re.MULTILINE),
    "const": re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?const\b", re.MULTILINE),
    "struct": re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?struct\b", re.MULTILINE),
    "enum": re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?enum\b", re.MULTILINE),
    "trait": re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?trait\b", re.MULTILINE),
    "type": re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?type\b", re.MULTILINE),
    "impl": re.compile(r"^\s*impl\b", re.MULTILINE),
    "extern": re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?(?:unsafe\s+)?extern\b", re.MULTILINE),
    "macro_rules": re.compile(r"^\s*macro_rules!\b", re.MULTILINE),
}
STARTUP_ENV_STRUCT_RE = re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?struct\s+StartupEnv\b", re.MULTILINE)
STARTUP_ENV_IMPL_RE = re.compile(r"^\s*impl\s+StartupEnv\b", re.MULTILINE)


@dataclass(frozen=True)
class Violation:
    path: str
    message: str


def read_production_source(path: Path) -> str:
    return production_source(path.read_text(encoding="utf-8"))


def adapter_contract_violations(relative_path: Path, source_text: str) -> list[Violation]:
    violations: list[Violation] = []

    for item_name, pattern in TOP_LEVEL_FORBIDDEN_ITEM_PATTERNS.items():
        if pattern.search(source_text):
            violations.append(
                Violation(
                    path=relative_path.as_posix(),
                    message=f"adapter file defines forbidden top-level {item_name} item",
                )
            )

    for fn_name in TOP_LEVEL_FN_RE.findall(source_text):
        if not fn_name.endswith("_entry"):
            violations.append(
                Violation(
                    path=relative_path.as_posix(),
                    message=f"adapter file defines non-entry function `{fn_name}`",
                )
            )

    return violations


def is_parse_owned_symbol(fn_name: str) -> bool:
    return fn_name.startswith(PARSE_OWNED_FN_PREFIXES) or fn_name in PARSE_OWNED_FN_NAMES


def config_ownership_violations(relative_path: Path, source_text: str) -> list[Violation]:
    violations: list[Violation] = []
    in_parse = relative_path.is_relative_to(CONFIG_PARSE_ROOT)

    for fn_name in TOP_LEVEL_FN_RE.findall(source_text):
        if is_parse_owned_symbol(fn_name) and not in_parse:
            violations.append(
                Violation(
                    path=relative_path.as_posix(),
                    message=f"parse-owned function `{fn_name}` must live under {CONFIG_PARSE_ROOT.as_posix()}",
                )
            )

    if (STARTUP_ENV_STRUCT_RE.search(source_text) or STARTUP_ENV_IMPL_RE.search(source_text)) and not in_parse:
        violations.append(
            Violation(
                path=relative_path.as_posix(),
                message=f"`StartupEnv` must live under {CONFIG_PARSE_ROOT.as_posix()}",
            )
        )

    return violations


def collect_violations(repo_root: Path) -> list[Violation]:
    violations: list[Violation] = []

    for relative_path in ADAPTER_FILES:
        source_path = repo_root / relative_path
        violations.extend(adapter_contract_violations(relative_path, read_production_source(source_path)))

    for source_path in sorted((repo_root / CONFIG_ROOT).rglob("*.rs")):
        relative_path = source_path.relative_to(repo_root)
        violations.extend(config_ownership_violations(relative_path, read_production_source(source_path)))

    return violations


def format_summary(violations: list[Violation]) -> str:
    lines = ["Native architecture contracts", f"Violations: {len(violations)}"]
    for violation in violations:
        lines.append(f"  - {violation.path}: {violation.message}")
    return "\n".join(lines)


def main() -> int:
    violations = collect_violations(REPO_ROOT)
    print(format_summary(violations))
    return 1 if violations else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Native architecture contract verification failed: {exc}", file=sys.stderr)
        raise
