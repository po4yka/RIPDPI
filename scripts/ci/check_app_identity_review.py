#!/usr/bin/env python3
"""Validate the per-release Android application-identity review.

The review is intentionally checked in and refreshed by a human for every app
version. CI supplies the resolved Android Components variant manifest; this
script verifies that the review covers the live version, every release variant,
the variants published by the release workflow, and the reviewed package-ID
catalog without fetching network data.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import date
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
REVIEW_PATH = ROOT / "quality/release-gates/app-identity-review.json"
RESOLVED_PATH = ROOT / "app/build/reports/app-identity/release-identity.json"
RELEASE_WORKFLOW_PATH = ROOT / ".github/workflows/release.yml"

SCHEMA_VERSION = 1
REQUIRED_SOURCE_IDS = {
    "app-level-vpn-detection",
    "mintsifry-vpn-detection-methodology",
    "rknhardering-vpn-app-catalog",
}
ALLOWED_DECISIONS = {"retain-stable-id", "change-id", "accept-known-match"}
RECOGNIZABLE_TOKENS = {"bypass", "dpi", "proxy", "ripdpi", "tor", "vpn"}
PACKAGE_ID_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$")
SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
RELEASE_TASK_RE = re.compile(r"\b((?:assemble|bundle)([A-Z][A-Za-z0-9]*Release))\b")


def load_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path}: top-level JSON value must be an object")
    return value


def require_object(value: object, label: str) -> dict:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    return value


def require_string(value: object, label: str, minimum_length: int = 1) -> str:
    if not isinstance(value, str) or len(value.strip()) < minimum_length:
        raise ValueError(f"{label} must be a non-empty string")
    return value


def require_string_list(value: object, label: str) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise ValueError(f"{label} must be a list of strings")
    if len(value) != len(set(value)):
        raise ValueError(f"{label} must not contain duplicates")
    if value != sorted(value):
        raise ValueError(f"{label} must be sorted")
    return value


def require_date(value: object, label: str) -> str:
    text = require_string(value, label)
    try:
        date.fromisoformat(text)
    except ValueError as exc:
        raise ValueError(f"{label} must use YYYY-MM-DD") from exc
    return text


def validate_package_id(value: object, label: str) -> str:
    package_id = require_string(value, label)
    if not PACKAGE_ID_RE.fullmatch(package_id):
        raise ValueError(f"{label} is not a valid Android package ID: {package_id!r}")
    return package_id


def normalize_resolved_variants(resolved: dict) -> dict[str, dict[str, str]]:
    variants = resolved.get("variants")
    if not isinstance(variants, list) or not variants:
        raise ValueError("resolved variants must be a non-empty list")
    normalized: dict[str, dict[str, str]] = {}
    for index, raw_variant in enumerate(variants):
        variant = require_object(raw_variant, f"resolved variant #{index}")
        name = require_string(variant.get("name"), f"resolved variant #{index}.name")
        if name in normalized:
            raise ValueError(f"duplicate resolved variant: {name}")
        normalized[name] = {
            "name": name,
            "distribution": require_string(
                variant.get("distribution"),
                f"resolved variant {name}.distribution",
            ),
            "experience": require_string(
                variant.get("experience"),
                f"resolved variant {name}.experience",
            ),
            "applicationId": validate_package_id(
                variant.get("applicationId"),
                f"resolved variant {name}.applicationId",
            ),
        }
    return normalized


def normalize_review_variants(review: dict) -> dict[str, dict]:
    variants = review.get("variants")
    if not isinstance(variants, list) or not variants:
        raise ValueError("review variants must be a non-empty list")
    normalized: dict[str, dict] = {}
    for index, raw_variant in enumerate(variants):
        variant = require_object(raw_variant, f"review variant #{index}")
        name = require_string(variant.get("name"), f"review variant #{index}.name")
        if name in normalized:
            raise ValueError(f"duplicate review variant: {name}")
        published = variant.get("published")
        if not isinstance(published, bool):
            raise ValueError(f"review variant {name}.published must be a boolean")
        normalized[name] = {
            "name": name,
            "distribution": require_string(
                variant.get("distribution"),
                f"review variant {name}.distribution",
            ),
            "experience": require_string(
                variant.get("experience"),
                f"review variant {name}.experience",
            ),
            "applicationId": validate_package_id(
                variant.get("applicationId"),
                f"review variant {name}.applicationId",
            ),
            "published": published,
        }
    return normalized


def extract_release_tasks(workflow_text: str) -> list[str]:
    return sorted({match.group(1) for match in RELEASE_TASK_RE.finditer(workflow_text)})


def variant_name_for_release_task(task_name: str) -> str:
    for prefix in ("assemble", "bundle"):
        if task_name.startswith(prefix):
            suffix = task_name.removeprefix(prefix)
            return suffix[:1].lower() + suffix[1:]
    raise ValueError(f"unsupported release task: {task_name}")


def validate_sources(review: dict, reviewed_at: str) -> None:
    sources = review.get("sources")
    if not isinstance(sources, list) or not sources:
        raise ValueError("sources must be a non-empty list")
    seen: set[str] = set()
    for index, raw_source in enumerate(sources):
        source = require_object(raw_source, f"source #{index}")
        source_id = require_string(source.get("id"), f"source #{index}.id")
        if source_id in seen:
            raise ValueError(f"duplicate source id: {source_id}")
        seen.add(source_id)
        require_string(source.get("repository"), f"source {source_id}.repository")
        require_string(source.get("path"), f"source {source_id}.path")
        revision = require_string(source.get("revision"), f"source {source_id}.revision")
        content_sha1 = require_string(
            source.get("contentSha1"),
            f"source {source_id}.contentSha1",
        )
        if not SHA1_RE.fullmatch(revision):
            raise ValueError(f"source {source_id}.revision must be a 40-character Git SHA-1")
        if not SHA1_RE.fullmatch(content_sha1):
            raise ValueError(f"source {source_id}.contentSha1 must be a 40-character Git blob SHA-1")
        if require_date(source.get("reviewedAt"), f"source {source_id}.reviewedAt") != reviewed_at:
            raise ValueError(f"source {source_id}.reviewedAt must match the release review date")
    missing = REQUIRED_SOURCE_IDS - seen
    if missing:
        raise ValueError("sources are missing required entries: " + ", ".join(sorted(missing)))


def validate_review(review: dict, resolved: dict, workflow_text: str) -> dict:
    if review.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError(f"unexpected review schemaVersion: {review.get('schemaVersion')!r}")
    if resolved.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError(f"unexpected resolved schemaVersion: {resolved.get('schemaVersion')!r}")

    release = require_object(review.get("reviewedRelease"), "reviewedRelease")
    reviewed_version_name = require_string(release.get("versionName"), "reviewedRelease.versionName")
    reviewed_version_code = release.get("versionCode")
    if not isinstance(reviewed_version_code, int) or isinstance(reviewed_version_code, bool):
        raise ValueError("reviewedRelease.versionCode must be an integer")
    reviewed_at = require_date(release.get("reviewedAt"), "reviewedRelease.reviewedAt")
    if resolved.get("versionName") != reviewed_version_name:
        raise ValueError(
            f"stale review versionName: reviewed {reviewed_version_name!r}, "
            f"resolved {resolved.get('versionName')!r}"
        )
    if resolved.get("versionCode") != reviewed_version_code:
        raise ValueError(
            f"stale review versionCode: reviewed {reviewed_version_code!r}, "
            f"resolved {resolved.get('versionCode')!r}"
        )

    validate_sources(review, reviewed_at)
    resolved_variants = normalize_resolved_variants(resolved)
    review_variants = normalize_review_variants(review)
    if set(review_variants) != set(resolved_variants):
        missing = sorted(set(resolved_variants) - set(review_variants))
        stale = sorted(set(review_variants) - set(resolved_variants))
        raise ValueError(f"release variant drift: missing={missing}, stale={stale}")
    for name, resolved_variant in resolved_variants.items():
        reviewed_variant = review_variants[name]
        reviewed_core = {key: reviewed_variant[key] for key in resolved_variant}
        if reviewed_core != resolved_variant:
            raise ValueError(
                f"release variant drift for {name}: reviewed={reviewed_core}, resolved={resolved_variant}"
            )

    reviewed_tasks = require_string_list(review.get("publishedReleaseTasks"), "publishedReleaseTasks")
    workflow_tasks = extract_release_tasks(workflow_text)
    if reviewed_tasks != workflow_tasks:
        raise ValueError(
            f"release workflow drift: reviewed tasks={reviewed_tasks}, workflow tasks={workflow_tasks}"
        )
    published_variants = sorted(name for name, variant in review_variants.items() if variant["published"])
    workflow_variants = sorted(variant_name_for_release_task(task) for task in workflow_tasks)
    if published_variants != workflow_variants:
        raise ValueError(
            f"published variant drift: reviewed={published_variants}, workflow={workflow_variants}"
        )

    catalog = require_object(review.get("catalog"), "catalog")
    package_ids = require_string_list(catalog.get("packageIds"), "catalog.packageIds")
    for index, package_id in enumerate(package_ids):
        validate_package_id(package_id, f"catalog.packageIds[{index}]")
    reviewed_matches = require_string_list(catalog.get("exactMatches"), "catalog.exactMatches")
    application_ids = {variant["applicationId"] for variant in resolved_variants.values()}
    derived_matches = sorted(application_ids & set(package_ids))
    if reviewed_matches != derived_matches:
        raise ValueError(
            f"catalog exactMatches drift: reviewed={reviewed_matches}, derived={derived_matches}"
        )

    assessment = require_object(review.get("assessment"), "assessment")
    recognizable_tokens = sorted(
        token
        for token in RECOGNIZABLE_TOKENS
        if any(token in application_id.lower() for application_id in application_ids)
    )
    reviewed_tokens = require_string_list(
        assessment.get("recognizableTokens"),
        "assessment.recognizableTokens",
    )
    if reviewed_tokens != recognizable_tokens:
        raise ValueError(
            f"recognizable token drift: reviewed={reviewed_tokens}, derived={recognizable_tokens}"
        )
    expected_risk = "high" if derived_matches else "elevated" if recognizable_tokens else "low"
    if assessment.get("riskLevel") != expected_risk:
        raise ValueError(
            f"assessment.riskLevel must be {expected_risk!r} for the resolved identity findings"
        )
    expected_exposure = "self-identifying" if recognizable_tokens else "opaque"
    if assessment.get("identityExposure") != expected_exposure:
        raise ValueError(f"assessment.identityExposure must be {expected_exposure!r}")
    if assessment.get("packageVisibility") != "targetable-via-explicit-queries":
        raise ValueError("assessment.packageVisibility must acknowledge targeted Android queries")
    if assessment.get("upgradeContinuity") != "application-id-change-breaks-in-place-updates":
        raise ValueError("assessment.upgradeContinuity must acknowledge the application-ID migration cost")

    decision = require_object(review.get("decision"), "decision")
    action = require_string(decision.get("action"), "decision.action")
    if action not in ALLOWED_DECISIONS:
        raise ValueError(f"decision.action must be one of {sorted(ALLOWED_DECISIONS)}")
    if decision.get("status") != "approved":
        raise ValueError("decision.status must be 'approved'")
    require_string(decision.get("reviewer"), "decision.reviewer")
    if require_date(decision.get("approvedAt"), "decision.approvedAt") != reviewed_at:
        raise ValueError("decision.approvedAt must match the release review date")
    require_string(decision.get("rationale"), "decision.rationale", minimum_length=20)
    if derived_matches:
        if action == "accept-known-match":
            require_string(decision.get("acceptedRisk"), "decision.acceptedRisk", minimum_length=20)
        elif action == "change-id":
            require_string(decision.get("migrationPlan"), "decision.migrationPlan", minimum_length=20)
        else:
            raise ValueError(
                "known catalog match requires decision.action 'accept-known-match' or 'change-id'"
            )
    elif action == "accept-known-match":
        raise ValueError("decision.action 'accept-known-match' requires an exact catalog match")
    elif action == "change-id":
        require_string(decision.get("migrationPlan"), "decision.migrationPlan", minimum_length=20)

    return {
        "versionName": reviewed_version_name,
        "versionCode": reviewed_version_code,
        "variantCount": len(resolved_variants),
        "publishedVariantCount": len(published_variants),
        "catalogSize": len(package_ids),
        "exactMatches": derived_matches,
        "recognizableTokens": recognizable_tokens,
        "riskLevel": expected_risk,
        "decision": action,
    }


def render_report(summary: dict) -> str:
    exact_matches = ", ".join(summary["exactMatches"]) or "none"
    recognizable_tokens = ", ".join(summary["recognizableTokens"]) or "none"
    return "\n".join(
        [
            "### Android app identity review",
            "",
            "- Verdict: **PASS**",
            f"- Release: `{summary['versionName']}` (code `{summary['versionCode']}`)",
            f"- Release variants: `{summary['variantCount']}` (`{summary['publishedVariantCount']}` published)",
            f"- Reviewed catalog IDs: `{summary['catalogSize']}`",
            f"- Exact catalog matches: `{exact_matches}`",
            f"- Recognizable identity tokens: `{recognizable_tokens}`",
            f"- Risk: `{summary['riskLevel']}`",
            f"- Decision: `{summary['decision']}`",
        ]
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review", default=str(REVIEW_PATH))
    parser.add_argument("--resolved", default=str(RESOLVED_PATH))
    parser.add_argument("--workflow", default=str(RELEASE_WORKFLOW_PATH))
    parser.add_argument("--report", action="store_true")
    args = parser.parse_args(argv)

    review_path = Path(args.review)
    resolved_path = Path(args.resolved)
    workflow_path = Path(args.workflow)
    try:
        review = load_json(review_path)
        resolved = load_json(resolved_path)
        workflow_text = workflow_path.read_text(encoding="utf-8")
        summary = validate_review(review, resolved, workflow_text)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        print(f"FAIL: Android app identity review: {exc}", file=sys.stderr)
        return 1

    if args.report:
        print(render_report(summary))
    else:
        print(
            "PASS: Android app identity review "
            f"{summary['versionName']} ({summary['variantCount']} variants, "
            f"decision={summary['decision']})"
        )
        if summary["recognizableTokens"]:
            print(
                "WARN: application IDs are self-identifying via tokens: "
                + ", ".join(summary["recognizableTokens"])
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
