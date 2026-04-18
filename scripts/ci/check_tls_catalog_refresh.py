#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
from datetime import date, datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CATALOG_PATH = ROOT / "app/src/main/assets/strategy-packs/catalog.json"
REFRESH_LOG_PATH = ROOT / "docs/strategy-pack-tls-refresh-log.json"
ACCEPTANCE_CORPUS_PATH = ROOT / "contract-fixtures/phase11_tls_template_acceptance.json"
ACCEPTANCE_REPORT_PATH = ROOT / "docs/tls-template-acceptance-report.json"
ACCEPTANCE_REPORT_REF = "tls_template_acceptance_report"


def load_module(module_name: str, relative_path: str):
    module_path = ROOT / relative_path
    spec = importlib.util.spec_from_file_location(module_name, module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


tls_template_acceptance = load_module("tls_template_acceptance", "scripts/ci/check_tls_template_acceptance.py")


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def parse_reviewed_at(value: str) -> date:
    return datetime.strptime(value, "%Y-%m-%d").date()


def validate_refresh_log(log: dict, catalog: dict, corpus: dict, report: dict, today: date | None = None) -> dict:
    if log.get("version") != "tls_catalog_refresh_log_v1":
        raise ValueError("unexpected TLS refresh log version")

    acceptance_summary = tls_template_acceptance.validate_acceptance_artifacts(catalog, corpus, report)

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
            "acceptanceReportRef": entry.get("acceptanceReportRef"),
            "allowedProfileIds": sorted(entry.get("allowedProfileIds", [])),
            "reviewedAt": entry.get("reviewedAt"),
        }
        for entry in catalog.get("tlsProfiles", [])
    }
    logged_profiles = {entry["id"]: entry for entry in latest_entry.get("profileSets", [])}

    missing = sorted(set(catalog_profiles) - set(logged_profiles))
    if missing:
        raise ValueError(f"latest TLS refresh log entry is missing profile sets: {', '.join(missing)}")

    mismatched_versions: list[str] = []
    mismatched_corpus_refs: list[str] = []
    mismatched_report_refs: list[str] = []
    mismatched_allowed_profiles: list[str] = []
    mismatched_reviewed_at: list[str] = []
    for profile_id, profile in catalog_profiles.items():
        logged = logged_profiles[profile_id]
        if logged.get("catalogVersion") != profile.get("catalogVersion"):
            mismatched_versions.append(profile_id)
        if logged.get("acceptanceCorpusRef") != profile.get("acceptanceCorpusRef"):
            mismatched_corpus_refs.append(profile_id)
        if logged.get("acceptanceReportRef") != profile.get("acceptanceReportRef"):
            mismatched_report_refs.append(profile_id)
        if sorted(logged.get("allowedProfileIds", [])) != profile.get("allowedProfileIds"):
            mismatched_allowed_profiles.append(profile_id)
        if logged.get("reviewedAt") != profile.get("reviewedAt"):
            mismatched_reviewed_at.append(profile_id)
        if profile.get("acceptanceCorpusRef") != acceptance_summary["corpusId"]:
            raise ValueError(f"catalog acceptanceCorpusRef drift for: {profile_id}")
        if profile.get("acceptanceReportRef") != ACCEPTANCE_REPORT_REF:
            raise ValueError(f"catalog acceptanceReportRef drift for: {profile_id}")
        if not set(profile.get("allowedProfileIds", [])).issubset(acceptance_summary["profileIds"]):
            raise ValueError(f"catalog allowedProfileIds drift for: {profile_id}")
    if mismatched_versions:
        raise ValueError(
            "latest TLS refresh log entry has catalogVersion drift for: " + ", ".join(sorted(mismatched_versions))
        )
    if mismatched_corpus_refs:
        raise ValueError(
            "latest TLS refresh log entry has acceptanceCorpusRef drift for: "
            + ", ".join(sorted(mismatched_corpus_refs))
        )
    if mismatched_report_refs:
        raise ValueError(
            "latest TLS refresh log entry has acceptanceReportRef drift for: "
            + ", ".join(sorted(mismatched_report_refs))
        )
    if mismatched_allowed_profiles:
        raise ValueError(
            "latest TLS refresh log entry has allowedProfileIds drift for: "
            + ", ".join(sorted(mismatched_allowed_profiles))
        )
    if mismatched_reviewed_at:
        raise ValueError(
            "latest TLS refresh log entry has reviewedAt drift for: " + ", ".join(sorted(mismatched_reviewed_at))
        )

    return {
        "latestReviewedAt": latest_entry["reviewedAt"],
        "ageDays": age_days,
        "cadenceDays": cadence_days,
        "profileSetIds": sorted(catalog_profiles),
        "acceptanceCorpusRef": acceptance_summary["corpusId"],
        "acceptanceReportRef": ACCEPTANCE_REPORT_REF,
    }


def main() -> int:
    summary = validate_refresh_log(
        load_json(REFRESH_LOG_PATH),
        load_json(CATALOG_PATH),
        load_json(ACCEPTANCE_CORPUS_PATH),
        load_json(ACCEPTANCE_REPORT_PATH),
    )
    print("TLS catalog refresh check")
    print(f"Latest review: {summary['latestReviewedAt']} ({summary['ageDays']} days old)")
    print(f"Cadence: every {summary['cadenceDays']} days")
    print("Profile sets: " + ", ".join(summary["profileSetIds"]))
    print(f"Acceptance corpus: {summary['acceptanceCorpusRef']}")
    print(f"Acceptance report: {summary['acceptanceReportRef']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
