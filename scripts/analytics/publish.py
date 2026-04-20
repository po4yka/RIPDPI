from __future__ import annotations

from pathlib import Path
from typing import Any

from .common import (
    DEFAULT_BLESSED_DEVICE_FINGERPRINT_CATALOG,
    DEFAULT_BLESSED_WINNER_MAPPING_CATALOG,
    DEVICE_FINGERPRINT_CATALOG_SCHEMA_VERSION,
    DEVICE_FINGERPRINT_WINNER_MAPPING_SCHEMA_VERSION,
    FEATURE_SCHEMA_VERSION,
    load_json_optional,
    now_iso_utc,
    write_json,
    write_text,
)


def publish_outputs(
    *,
    extracted_payload: dict[str, Any],
    clustered_payload: dict[str, Any],
    output_dir: Path,
    corpus_name: str,
    blessed_device_catalog_path: Path = DEFAULT_BLESSED_DEVICE_FINGERPRINT_CATALOG,
    blessed_winner_mapping_path: Path = DEFAULT_BLESSED_WINNER_MAPPING_CATALOG,
) -> dict[str, Path]:
    blessed_device_catalog = load_json_optional(blessed_device_catalog_path) or empty_device_catalog(review_status="reviewed")
    device_catalog = build_device_fingerprint_catalog(
        clustered_payload=clustered_payload,
        corpus_name=corpus_name,
        previous_catalog=blessed_device_catalog,
    )
    winner_catalog = build_winner_mapping_catalog(
        extracted_payload=extracted_payload,
        device_catalog=device_catalog,
        corpus_name=corpus_name,
    )
    drift_report = build_drift_report(device_catalog, blessed_device_catalog)
    report_text = build_markdown_report(device_catalog, winner_catalog, drift_report)

    output_dir.mkdir(parents=True, exist_ok=True)
    records_path = output_dir / "offline-records.json"
    clusters_path = output_dir / "device-fingerprint-catalog.candidate.json"
    winners_path = output_dir / "device-fingerprint-winner-mappings.candidate.json"
    drift_path = output_dir / "drift-report.json"
    report_path = output_dir / "report.md"
    write_json(records_path, extracted_payload)
    write_json(clusters_path, device_catalog)
    write_json(winners_path, winner_catalog)
    write_json(drift_path, drift_report)
    write_text(report_path, report_text)
    return {
        "records": records_path,
        "deviceCatalog": clusters_path,
        "winnerCatalog": winners_path,
        "driftReport": drift_path,
        "report": report_path,
    }


def empty_device_catalog(review_status: str) -> dict[str, Any]:
    return {
        "schemaVersion": DEVICE_FINGERPRINT_CATALOG_SCHEMA_VERSION,
        "featureSchemaVersion": FEATURE_SCHEMA_VERSION,
        "generatedAt": "",
        "reviewStatus": review_status,
        "reviewedAt": "",
        "corpusName": "",
        "sourceArchiveCount": 0,
        "clusterCount": 0,
        "clusters": [],
    }


def build_device_fingerprint_catalog(
    *,
    clustered_payload: dict[str, Any],
    corpus_name: str,
    previous_catalog: dict[str, Any],
) -> dict[str, Any]:
    previous_hashes = {
        cluster.get("clusterFingerprintHash"): cluster
        for cluster in previous_catalog.get("clusters", [])
        if cluster.get("clusterFingerprintHash")
    }
    clusters = []
    for cluster in clustered_payload.get("clusters", []):
        fingerprint_hash = cluster["clusterFingerprintHash"]
        previous = previous_hashes.get(fingerprint_hash)
        updated = dict(cluster)
        updated["novelty"] = "known" if previous else "new"
        updated["driftState"] = "stable" if previous else "new"
        clusters.append(updated)
    return {
        "schemaVersion": DEVICE_FINGERPRINT_CATALOG_SCHEMA_VERSION,
        "featureSchemaVersion": FEATURE_SCHEMA_VERSION,
        "generatedAt": now_iso_utc(),
        "reviewStatus": "unreviewed",
        "reviewedAt": "",
        "corpusName": corpus_name,
        "sourceArchiveCount": clustered_payload.get("recordCount", 0),
        "clusterCount": len(clusters),
        "clusters": clusters,
    }


def build_winner_mapping_catalog(
    *,
    extracted_payload: dict[str, Any],
    device_catalog: dict[str, Any],
    corpus_name: str,
) -> dict[str, Any]:
    record_lookup = {
        record["recordId"]: record
        for record in extracted_payload.get("records", [])
    }
    mappings = []
    for cluster in device_catalog.get("clusters", []):
        support_count = cluster["supportCount"]
        winner_groups: dict[tuple[str, str], list[dict[str, Any]]] = {}
        for record_id in cluster["records"]:
            record = record_lookup[record_id]
            winner_family = record["winnerSummary"].get("family")
            signature_hash = record["winnerSummary"].get("signatureHash")
            if not winner_family or not signature_hash:
                continue
            winner_groups.setdefault((winner_family, signature_hash), []).append(record)
        if not winner_groups:
            continue
        best_key, best_records = max(winner_groups.items(), key=lambda item: (len(item[1]), item[0][0], item[0][1]))
        support = len(best_records)
        if support < max(2, support_count // 2):
            continue
        winner_family, signature_hash = best_key
        reproducibility = round(support / support_count, 2)
        mappings.append(
            {
                "deviceFingerprintId": cluster["id"],
                "clusterFingerprintHash": cluster["clusterFingerprintHash"],
                "recommendedWinnerFamily": winner_family,
                "recommendedWinnerSignatureHash": signature_hash,
                "supportCount": support,
                "reproducibilityScore": reproducibility,
                "negativeEvidenceCount": support_count - support,
                "sourceArchiveCount": support_count,
            }
        )
    return {
        "schemaVersion": DEVICE_FINGERPRINT_WINNER_MAPPING_SCHEMA_VERSION,
        "featureSchemaVersion": FEATURE_SCHEMA_VERSION,
        "generatedAt": now_iso_utc(),
        "reviewStatus": "unreviewed",
        "reviewedAt": "",
        "corpusName": corpus_name,
        "mappingCount": len(mappings),
        "mappings": mappings,
    }


def build_drift_report(
    device_catalog: dict[str, Any],
    previous_catalog: dict[str, Any],
) -> dict[str, Any]:
    previous_hashes = {cluster.get("clusterFingerprintHash") for cluster in previous_catalog.get("clusters", [])}
    current_hashes = {cluster.get("clusterFingerprintHash") for cluster in device_catalog.get("clusters", [])}
    return {
        "generatedAt": now_iso_utc(),
        "newClusterCount": len(current_hashes - previous_hashes),
        "stableClusterCount": len(current_hashes & previous_hashes),
        "retiredClusterCount": len(previous_hashes - current_hashes),
        "newClusterIds": [
            cluster["id"]
            for cluster in device_catalog.get("clusters", [])
            if cluster.get("clusterFingerprintHash") not in previous_hashes
        ],
    }


def build_markdown_report(
    device_catalog: dict[str, Any],
    winner_catalog: dict[str, Any],
    drift_report: dict[str, Any],
) -> str:
    lines = [
        "# Offline Analytics Report",
        "",
        f"- Generated at: {device_catalog['generatedAt']}",
        f"- Corpus: {device_catalog['corpusName']}",
        f"- Source archives: {device_catalog['sourceArchiveCount']}",
        f"- Device clusters: {device_catalog['clusterCount']}",
        f"- Winner mappings: {winner_catalog['mappingCount']}",
        f"- New clusters vs blessed baseline: {drift_report['newClusterCount']}",
        "",
    ]
    for cluster in device_catalog.get("clusters", []):
        lines.extend(
            [
                f"## {cluster['id']}",
                "",
                f"- Bucket: {cluster['bucket']}",
                f"- Support: {cluster['supportCount']}",
                f"- Confidence: {cluster['confidence']}",
                f"- Drift: {cluster['driftState']}",
                f"- Dominant signals: {', '.join(cluster['dominantSignals']) or 'none'}",
                f"- Representative targets: {', '.join(cluster['representativeTargets']) or 'none'}",
                f"- Stable winners: {', '.join(item['family'] for item in cluster['stableWinnerFamilies']) or 'none'}",
                "",
            ]
        )
    return "\n".join(lines).rstrip() + "\n"

