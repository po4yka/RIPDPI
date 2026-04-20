from __future__ import annotations

import csv
import hashlib
import io
import json
import re
from collections.abc import Iterable
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
OFFLINE_RECORD_SCHEMA_VERSION = 1
DEVICE_FINGERPRINT_CATALOG_SCHEMA_VERSION = 1
DEVICE_FINGERPRINT_WINNER_MAPPING_SCHEMA_VERSION = 1
FEATURE_SCHEMA_VERSION = 1
DEFAULT_BLESSED_DEVICE_FINGERPRINT_CATALOG = (
    ROOT / "app/src/main/assets/offline-analytics/device-fingerprint-catalog.json"
)
DEFAULT_BLESSED_WINNER_MAPPING_CATALOG = (
    ROOT / "app/src/main/assets/offline-analytics/device-fingerprint-winner-mappings.json"
)
TARGET_LABEL_MAX = 80
EMPTY_VALUE = "none"

SUCCESS_OUTCOMES = {
    "dns_match",
    "http_ok",
    "network_available",
    "quic_initial_response",
    "quic_response",
    "reachable",
    "tls_ech_only",
    "tls_ok",
    "whitelist_sni_ok",
}
FAILURE_KEYWORDS = (
    "blocked",
    "divergence",
    "error",
    "failed",
    "mismatch",
    "redirect",
    "reset",
    "split",
    "timeout",
    "unreachable",
)
RUNTIME_COMPONENT_KEYS = ("proxy", "tunnel", "relay", "warp")


def now_iso_utc() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def load_json_optional(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    return load_json(path)


def load_csv_rows(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    return list(csv.DictReader(io.StringIO(path.read_text(encoding="utf-8"))))


def parse_probe_detail_json(payload: str | None) -> dict[str, str]:
    if not payload:
        return {}
    parsed = json.loads(payload)
    if isinstance(parsed, list):
        details: dict[str, str] = {}
        for item in parsed:
            if isinstance(item, dict) and "key" in item:
                details[str(item.get("key", ""))] = str(item.get("value", ""))
        return details
    if isinstance(parsed, dict):
        return {str(key): stringify_scalar(value) for key, value in parsed.items()}
    return {}


def stringify_scalar(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    return str(value)


def stable_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(stable_json_dumps(payload) + "\n", encoding="utf-8")


def stable_json_dumps(payload: Any) -> str:
    return json.dumps(payload, indent=2, ensure_ascii=True, sort_keys=False)


def write_text(path: Path, payload: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(payload, encoding="utf-8")


def slugify(value: str) -> str:
    normalized = re.sub(r"[^a-z0-9]+", "_", value.strip().lower())
    return normalized.strip("_") or "unknown"


def normalize_winner_family(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = " ".join(value.split()).strip()
    return normalized or None


def split_pipe_values(value: str | None) -> list[str]:
    if not value:
        return []
    return [part.strip() for part in value.split("|") if part.strip()]


def split_comma_values(value: str | None) -> list[str]:
    if not value:
        return []
    return [part.strip() for part in value.split(",") if part.strip()]


def split_semicolon_values(value: str | None) -> list[str]:
    if not value:
        return []
    return [part.strip() for part in value.split(";") if part.strip()]


def is_success_outcome(outcome: str) -> bool:
    normalized = outcome.strip().lower()
    return normalized in SUCCESS_OUTCOMES


def is_failure_outcome(outcome: str) -> bool:
    normalized = outcome.strip().lower()
    if normalized in SUCCESS_OUTCOMES:
        return False
    return any(keyword in normalized for keyword in FAILURE_KEYWORDS)


def normalize_target_label(value: str) -> str:
    candidate = value.strip()
    if "·" in candidate:
        candidate = candidate.split("·", 1)[1].strip()
    if " (" in candidate and candidate.endswith(")"):
        candidate = candidate.split(" (", 1)[0].strip()
    candidate = candidate.lower()
    if re.fullmatch(r"\[?[0-9a-f:.]+\]?:\d+", candidate):
        return "ip_endpoint"
    if re.fullmatch(r"[0-9a-f:.]+", candidate):
        return "ip_endpoint"
    if len(candidate) > TARGET_LABEL_MAX:
        candidate = candidate[:TARGET_LABEL_MAX]
    return candidate


def classify_target_category(probe_type: str, target_label: str) -> str:
    normalized = normalize_target_label(target_label)
    if probe_type == "dns_integrity":
        return "dns"
    if probe_type == "domain_reachability":
        return "domain"
    if probe_type == "service_reachability":
        return f"service:{slugify(normalized)}"
    if probe_type == "circumvention_reachability":
        return f"circumvention:{slugify(normalized)}"
    if probe_type == "quic_reachability":
        return "quic"
    if probe_type == "tcp_fat_header":
        return "tcp_path"
    if probe_type == "throughput_window":
        return f"throughput:{slugify(normalized)}"
    if probe_type.startswith("strategy_"):
        return f"strategy:{probe_type.removeprefix('strategy_')}"
    if probe_type == "network_environment":
        return "network_environment"
    return slugify(probe_type)


def normalize_error_kind(value: str | None) -> str | None:
    if not value:
        return None
    normalized = value.strip().lower()
    if not normalized or normalized == EMPTY_VALUE:
        return None
    if "unexpected end of file" in normalized or "eof" in normalized:
        return "eof"
    if "os error 11" in normalized or "try again" in normalized:
        return "would_block"
    if "os error 101" in normalized or "network is unreachable" in normalized:
        return "network_unreachable"
    if "reset" in normalized:
        return "reset"
    if "timed out" in normalized or "timeout" in normalized:
        return "timeout"
    return slugify(normalized)


def dominant_values(values: Iterable[str], minimum_support: int = 1) -> list[str]:
    counts: dict[str, int] = {}
    for value in values:
        if not value:
            continue
        counts[value] = counts.get(value, 0) + 1
    return [
        value
        for value, count in sorted(counts.items(), key=lambda item: (-item[1], item[0]))
        if count >= minimum_support
    ]


def safe_relative_path(path: Path) -> str:
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path.resolve())

