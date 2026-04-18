#!/usr/bin/env python3
from __future__ import annotations

import json
from datetime import date, datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CATALOG_PATH = ROOT / "app/src/main/assets/strategy-packs/catalog.json"
REFRESH_LOG_PATH = ROOT / "docs/strategy-pack-tls-refresh-log.json"


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def parse_reviewed_at(value: str) -> date:
    return datetime.strptime(value, "%Y-%m-%d").date()


def validate_refresh_log(log: dict, catalog: dict, today: date | None = None) -> dict:
    if log.get("version") != "tls_catalog_refresh_log_v1":
        raise ValueError("unexpected TLS refresh log version")

    cadence_days = int(log.get("cadenceDays", 0))
    if cadence_days <= 0:
        raise ValueError("cadenceDays must be positive")

    entries = log.get("entries")
    if not isinstance(entries, list) or not entries:
        raise ValueError("TLS refresh log must contain at least one entry")

    latest_entry = max(entries, key=lambda entry: entry["reviewedAt"])
    latest_reviewed_at = parse_reviewed_at(latest_entry["reviewedAt"])
    today = today or date.today()
    age_days = (today - latest_reviewed_at).days
    if age_days > cadence_days:
        raise ValueError(
            f"latest TLS catalog review is stale: {latest_reviewed_at} is {age_days} days old (cadence {cadence_days})"
        )

    catalog_profiles = {
        entry["id"]: {
            "catalogVersion": entry.get("catalogVersion"),
            "acceptanceCorpusRef": entry.get("acceptanceCorpusRef"),
        }
        for entry in catalog.get("tlsProfiles", [])
    }
    logged_profiles = {entry["id"]: entry for entry in latest_entry.get("profileSets", [])}

    missing = sorted(set(catalog_profiles) - set(logged_profiles))
    if missing:
        raise ValueError(f"latest TLS refresh log entry is missing profile sets: {', '.join(missing)}")

    mismatched_versions: list[str] = []
    for profile_id, profile in catalog_profiles.items():
        logged = logged_profiles[profile_id]
        if logged.get("catalogVersion") != profile.get("catalogVersion"):
            mismatched_versions.append(profile_id)
    if mismatched_versions:
        raise ValueError(
            "latest TLS refresh log entry has catalogVersion drift for: " + ", ".join(sorted(mismatched_versions))
        )

    return {
        "latestReviewedAt": latest_entry["reviewedAt"],
        "ageDays": age_days,
        "cadenceDays": cadence_days,
        "profileSetIds": sorted(catalog_profiles),
    }


def main() -> int:
    summary = validate_refresh_log(load_json(REFRESH_LOG_PATH), load_json(CATALOG_PATH))
    print("TLS catalog refresh check")
    print(f"Latest review: {summary['latestReviewedAt']} ({summary['ageDays']} days old)")
    print(f"Cadence: every {summary['cadenceDays']} days")
    print("Profile sets: " + ", ".join(summary["profileSetIds"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
