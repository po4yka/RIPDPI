#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CATALOG_PATH = ROOT / "app/src/main/assets/strategy-packs/catalog.json"
CORPUS_PATH = ROOT / "contract-fixtures/phase11_tls_template_acceptance.json"
REPORT_PATH = ROOT / "docs/tls-template-acceptance-report.json"


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _accepted_stack_ids(results: list[dict]) -> list[str]:
    return sorted(
        {
            entry["stackId"]
            for entry in results
            if entry.get("status") == "accepted"
        }
    )


def validate_acceptance_artifacts(catalog: dict, corpus: dict, report: dict) -> dict:
    if corpus.get("schemaVersion") != 2:
        raise ValueError("unexpected TLS acceptance corpus schemaVersion")
    if report.get("version") != "tls_template_acceptance_report_v1":
        raise ValueError("unexpected TLS acceptance report version")

    corpus_id = corpus.get("corpusId")
    if not corpus_id:
        raise ValueError("TLS acceptance corpus must declare corpusId")
    if report.get("corpusRef") != corpus_id:
        raise ValueError("TLS acceptance report corpusRef drift")

    profile_sets = catalog.get("tlsProfiles", [])
    if not isinstance(profile_sets, list) or not profile_sets:
        raise ValueError("strategy-pack catalog must publish tlsProfiles")

    expected_profile_set_ids = sorted(entry["id"] for entry in profile_sets)
    if sorted(report.get("profileSetIds", [])) != expected_profile_set_ids:
        raise ValueError("TLS acceptance report profileSetIds drift")

    expected_profile_ids = sorted({profile_id for entry in profile_sets for profile_id in entry.get("allowedProfileIds", [])})
    for entry in profile_sets:
        if entry.get("acceptanceCorpusRef") != corpus_id:
            raise ValueError(f"strategy-pack catalog acceptanceCorpusRef drift for {entry.get('id')}")

    coverage_targets = corpus.get("coverageTargets", {})
    min_cdn = int(coverage_targets.get("minimumAcceptedCdnStacks", 0))
    min_server = int(coverage_targets.get("minimumAcceptedServerStacks", 0))
    min_total = int(coverage_targets.get("minimumAcceptedStacksPerProfile", 0))
    min_ech = int(coverage_targets.get("minimumAcceptedEchStacks", 0))

    stacks = corpus.get("stacks", [])
    stack_index = {entry["id"]: entry for entry in stacks}
    if len(stack_index) != len(stacks):
        raise ValueError("TLS acceptance corpus stack ids must be unique")

    corpus_profiles = corpus.get("profiles", [])
    report_profiles = report.get("profiles", [])
    corpus_profile_index = {entry["id"]: entry for entry in corpus_profiles}
    report_profile_index = {entry["id"]: entry for entry in report_profiles}

    if sorted(corpus_profile_index) != expected_profile_ids:
        raise ValueError("TLS acceptance corpus profile coverage drift")
    if sorted(report_profile_index) != expected_profile_ids:
        raise ValueError("TLS acceptance report profile coverage drift")

    ech_covered: set[str] = set()
    covered_cdns: set[str] = set()
    covered_servers: set[str] = set()

    for profile_id in expected_profile_ids:
        entry = corpus_profile_index[profile_id]
        results = entry.get("acceptanceResults")
        if not isinstance(results, list) or not results:
            raise ValueError(f"TLS acceptance corpus entry {profile_id} must contain acceptanceResults")

        accepted_stack_ids = _accepted_stack_ids(results)
        if accepted_stack_ids != sorted(entry.get("acceptedStacks", [])):
            raise ValueError(f"TLS acceptance corpus acceptedStacks drift for {profile_id}")

        cdn_count = 0
        server_count = 0
        ech_count = 0
        profile_ech_capable = bool(entry.get("echCapable"))
        for stack_id in accepted_stack_ids:
            stack = stack_index.get(stack_id)
            if stack is None:
                raise ValueError(f"TLS acceptance corpus references unknown stack {stack_id}")
            if stack["class"] == "cdn":
                cdn_count += 1
                covered_cdns.add(stack_id)
            elif stack["class"] == "server":
                server_count += 1
                covered_servers.add(stack_id)
            else:
                raise ValueError(f"TLS acceptance corpus stack {stack_id} has unknown class")
            if profile_ech_capable and stack.get("echCapable"):
                ech_count += 1
                ech_covered.add(stack_id)

        summary = entry.get("acceptanceSummary", {})
        if summary.get("acceptedCdnStacks") != cdn_count:
            raise ValueError(f"TLS acceptance summary cdn count drift for {profile_id}")
        if summary.get("acceptedServerStacks") != server_count:
            raise ValueError(f"TLS acceptance summary server count drift for {profile_id}")
        if summary.get("acceptedEchStacks") != ech_count:
            raise ValueError(f"TLS acceptance summary ech count drift for {profile_id}")
        if summary.get("acceptedTotalStacks") != len(accepted_stack_ids):
            raise ValueError(f"TLS acceptance summary total count drift for {profile_id}")

        if cdn_count < min_cdn:
            raise ValueError(f"TLS acceptance coverage below CDN threshold for {profile_id}")
        if server_count < min_server:
            raise ValueError(f"TLS acceptance coverage below server threshold for {profile_id}")
        if len(accepted_stack_ids) < min_total:
            raise ValueError(f"TLS acceptance coverage below total threshold for {profile_id}")
        if profile_ech_capable and ech_count < min_ech:
            raise ValueError(f"TLS acceptance coverage below ECH threshold for {profile_id}")

        report_entry = report_profile_index[profile_id]
        if sorted(report_entry.get("acceptedStacks", [])) != accepted_stack_ids:
            raise ValueError(f"TLS acceptance report stack list drift for {profile_id}")
        if report_entry.get("acceptedCdnStacks") != cdn_count:
            raise ValueError(f"TLS acceptance report cdn count drift for {profile_id}")
        if report_entry.get("acceptedServerStacks") != server_count:
            raise ValueError(f"TLS acceptance report server count drift for {profile_id}")
        if report_entry.get("acceptedEchStacks") != ech_count:
            raise ValueError(f"TLS acceptance report ech count drift for {profile_id}")

    stack_coverage = report.get("stackCoverage", {})
    if sorted(stack_coverage.get("cdnStacksCovered", [])) != sorted(covered_cdns):
        raise ValueError("TLS acceptance report CDN coverage drift")
    if sorted(stack_coverage.get("serverStacksCovered", [])) != sorted(covered_servers):
        raise ValueError("TLS acceptance report server coverage drift")
    if sorted(stack_coverage.get("echStacksCovered", [])) != sorted(ech_covered):
        raise ValueError("TLS acceptance report ECH coverage drift")

    return {
        "corpusId": corpus_id,
        "profileCount": len(expected_profile_ids),
        "profileIds": expected_profile_ids,
        "profileSetIds": expected_profile_set_ids,
        "cdnStacksCovered": sorted(covered_cdns),
        "serverStacksCovered": sorted(covered_servers),
        "echStacksCovered": sorted(ech_covered),
    }


def main() -> int:
    summary = validate_acceptance_artifacts(
        load_json(CATALOG_PATH),
        load_json(CORPUS_PATH),
        load_json(REPORT_PATH),
    )
    print("TLS template acceptance check")
    print(f"Corpus: {summary['corpusId']}")
    print(f"Profiles: {summary['profileCount']}")
    print("Profile sets: " + ", ".join(summary["profileSetIds"]))
    print("CDN stacks: " + ", ".join(summary["cdnStacksCovered"]))
    print("Server stacks: " + ", ".join(summary["serverStacksCovered"]))
    print("ECH-capable stacks: " + ", ".join(summary["echStacksCovered"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
