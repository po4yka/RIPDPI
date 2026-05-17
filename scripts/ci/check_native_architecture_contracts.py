#!/usr/bin/env python3

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

from check_native_hotspot_budgets import production_source


REPO_ROOT = Path(__file__).resolve().parents[2]
ADAPTER_FILES = (
    Path("native/rust/crates/ripdpi-android-proxy-adapter/src/lib.rs"),
    Path("native/rust/crates/ripdpi-android-diagnostics-adapter/src/lib.rs"),
    Path("native/rust/crates/ripdpi-tunnel-android/src/session.rs"),
)
CONFIG_ROOT = Path("native/rust/crates/ripdpi-config/src")
CONFIG_PARSE_ROOT = CONFIG_ROOT / "parse"
CONFIG_MODEL_PATH = CONFIG_ROOT / "model" / "mod.rs"
RUNTIME_DECISION_PORTS_PATH = Path("native/rust/crates/ripdpi-runtime-decision-ports/src/lib.rs")
RUNTIME_DECISION_PORTS_CARGO_PATH = Path("native/rust/crates/ripdpi-runtime-decision-ports/Cargo.toml")
MONITOR_ENGINE_CARGO_PATH = Path("native/rust/crates/ripdpi-monitor-engine/Cargo.toml")
DIAGNOSTICS_RUNNER_ADAPTER_FILES = (
    Path("native/rust/crates/ripdpi-diagnostics-runner/src/connectivity/adapters.rs"),
    Path("native/rust/crates/ripdpi-diagnostics-runner/src/strategy/adapters.rs"),
)
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
RUNTIME_DECISION_PORTS_FORBIDDEN_PATTERNS = {
    "broad module `adaptive`": re.compile(r"^\s*pub\s+mod\s+adaptive\b", re.MULTILINE),
    "broad module `direct_path_learning`": re.compile(r"^\s*pub\s+mod\s+direct_path_learning\b", re.MULTILINE),
    "broad module `policy`": re.compile(r"^\s*pub\s+mod\s+policy\b", re.MULTILINE),
    "adaptive morph-policy module re-export": re.compile(
        r"^\s*pub\s+use\s+ripdpi_runtime_adaptive::morph_policy(?=\s*;|::\*)",
        re.MULTILINE,
    ),
    "adaptive strategy-context module re-export": re.compile(
        r"^\s*pub\s+use\s+ripdpi_runtime_adaptive::strategy_context(?=\s*;|::\*)",
        re.MULTILINE,
    ),
    "policy engine module re-export": re.compile(
        r"^\s*pub\s+use\s+ripdpi_runtime_policy::runtime_policy(?=\s*;|::\*)",
        re.MULTILINE,
    ),
    "direct-path learning module re-export": re.compile(
        r"^\s*pub\s+use\s+ripdpi_runtime_policy::direct_path_learning(?=\s*;|::\*)",
        re.MULTILINE,
    ),
    "runtime policy engine type export": re.compile(r"\bRuntimePolicy\b"),
    "direct-path learning state export": re.compile(r"\bDirectPathLearningState\b"),
    "policy/adaptive helper export": re.compile(
        r"\b(?:"
        r"apply_udp_morph_policy_to_hints|apply_tcp_morph_policy_to_group|"
        r"tcp_morph_hint_family|udp_morph_hint_family|"
        r"direct_path_capability_for_route|merge_udp_hints_with_capability|network_scope_key|"
        r"classify_response_failure|response_requires_dns_tampering_evidence|"
        r"extract_host|extract_host_info|group_requires_payload|"
        r"is_tls_client_hello_payload|route_matches_payload"
        r")\b",
        re.MULTILINE,
    ),
}
RUNTIME_DECISION_PORTS_FORBIDDEN_DEP_RE = re.compile(
    r"^\s*(ripdpi-runtime-(?:adaptive|policy))\s*=",
    re.MULTILINE,
)
DIAGNOSTICS_LANE_BROAD_REEXPORT_RE = re.compile(
    r"^\s*pub\s+use\s+ripdpi_diagnostics_[A-Za-z0-9_]+::[A-Za-z0-9_:]+::\*\s*;",
    re.MULTILINE,
)
DIAGNOSTICS_LANE_BROAD_IMPORT_RE = re.compile(
    r"^\s*use\s+crate::(?:connectivity|strategy)::adapters::[A-Za-z0-9_]+::\*\s*;",
    re.MULTILINE,
)
MONITOR_ENGINE_CONCRETE_LANE_DEP_RE = re.compile(
    r"^\s*ripdpi-diagnostics-(?!contracts\b)[A-Za-z0-9-]+\s*=",
    re.MULTILINE,
)


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


def runtime_decision_ports_violations(relative_path: Path, source_text: str) -> list[Violation]:
    violations: list[Violation] = []

    for description, pattern in RUNTIME_DECISION_PORTS_FORBIDDEN_PATTERNS.items():
        if pattern.search(source_text):
            violations.append(
                Violation(
                    path=relative_path.as_posix(),
                    message=f"runtime decision ports must not expose {description}",
                )
            )

    return violations


def runtime_decision_ports_dependency_violations(relative_path: Path, source_text: str) -> list[Violation]:
    violations: list[Violation] = []

    for match in RUNTIME_DECISION_PORTS_FORBIDDEN_DEP_RE.finditer(source_text):
        dependency = match.group(1)
        violations.append(
            Violation(
                path=relative_path.as_posix(),
                message=f"runtime decision ports must not depend on concrete engine crate `{dependency}`",
            )
        )

    return violations


def diagnostics_lane_adapter_violations(relative_path: Path, source_text: str) -> list[Violation]:
    violations: list[Violation] = []

    if DIAGNOSTICS_LANE_BROAD_REEXPORT_RE.search(source_text):
        violations.append(
            Violation(
                path=relative_path.as_posix(),
                message="diagnostics lane adapters must re-export explicit symbols, not concrete crate globs",
            )
        )
    if DIAGNOSTICS_LANE_BROAD_IMPORT_RE.search(source_text):
        violations.append(
            Violation(
                path=relative_path.as_posix(),
                message="diagnostics runner callers must not depend on broad lane adapter globs",
            )
        )

    return violations


def monitor_engine_dependency_violations(relative_path: Path, source_text: str) -> list[Violation]:
    violations: list[Violation] = []
    for match in MONITOR_ENGINE_CONCRETE_LANE_DEP_RE.finditer(source_text):
        dependency = match.group(0).split("=", maxsplit=1)[0].strip()
        violations.append(
            Violation(
                path=relative_path.as_posix(),
                message=f"monitor-engine must not depend on concrete diagnostics lane crate `{dependency}`",
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

    runtime_ports_path = repo_root / RUNTIME_DECISION_PORTS_PATH
    violations.extend(
        runtime_decision_ports_violations(RUNTIME_DECISION_PORTS_PATH, read_production_source(runtime_ports_path))
    )
    runtime_ports_cargo = repo_root / RUNTIME_DECISION_PORTS_CARGO_PATH
    violations.extend(
        runtime_decision_ports_dependency_violations(
            RUNTIME_DECISION_PORTS_CARGO_PATH,
            runtime_ports_cargo.read_text(encoding="utf-8"),
        )
    )

    for relative_path in DIAGNOSTICS_RUNNER_ADAPTER_FILES:
        source_path = repo_root / relative_path
        violations.extend(diagnostics_lane_adapter_violations(relative_path, read_production_source(source_path)))

    monitor_engine_cargo = repo_root / MONITOR_ENGINE_CARGO_PATH
    violations.extend(
        monitor_engine_dependency_violations(MONITOR_ENGINE_CARGO_PATH, monitor_engine_cargo.read_text(encoding="utf-8"))
    )

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
