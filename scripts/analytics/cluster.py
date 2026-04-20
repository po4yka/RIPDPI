from __future__ import annotations

from collections import Counter, defaultdict
from typing import Any

from .common import (
    DEVICE_FINGERPRINT_CATALOG_SCHEMA_VERSION,
    FEATURE_SCHEMA_VERSION,
    dominant_values,
    now_iso_utc,
    slugify,
    stable_hash,
)


def run_cluster(extracted_payload: dict[str, Any]) -> dict[str, Any]:
    records = extracted_payload.get("records", [])
    records_by_bucket: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for record in records:
        records_by_bucket[coarse_bucket_for_record(record)].append(record)

    clusters = []
    for bucket in sorted(records_by_bucket):
        bucket_records = sorted(records_by_bucket[bucket], key=lambda record: record["recordId"])
        for cluster_records in greedy_cluster(bucket_records):
            clusters.append(build_cluster(bucket, cluster_records))

    return {
        "schemaVersion": DEVICE_FINGERPRINT_CATALOG_SCHEMA_VERSION,
        "featureSchemaVersion": FEATURE_SCHEMA_VERSION,
        "generatedAt": now_iso_utc(),
        "recordCount": len(records),
        "clusterCount": len(clusters),
        "clusters": clusters,
    }


def coarse_bucket_for_record(record: dict[str, Any]) -> str:
    assessment_code = record["connectivityAssessment"]["code"]
    features = set(record["observedFeatures"])
    if assessment_code == "service_runtime_failure":
        return "service_runtime"
    if assessment_code == "resolver_interference":
        return "resolver_interference"
    if any(feature.startswith("tls_outcome:tls_version_split") for feature in features):
        return "tls_split"
    if any(feature.startswith("http_redirect:") for feature in features):
        return "http_redirect"
    if "route_sensitive:true" in features:
        return "route_sensitive"
    if assessment_code == "raw_network_general_failure":
        return "general_outage"
    return "mixed"


def greedy_cluster(records: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    clusters: list[list[dict[str, Any]]] = []
    for record in records:
        best_index = None
        best_score = 0.0
        record_features = clustering_features(record)
        for index, cluster in enumerate(clusters):
            score = jaccard(record_features, cluster_union_features(cluster))
            if score > best_score:
                best_score = score
                best_index = index
        if best_index is not None and best_score >= 0.45:
            clusters[best_index].append(record)
        else:
            clusters.append([record])
    return clusters


def clustering_features(record: dict[str, Any]) -> set[str]:
    return {feature for feature in record["observedFeatures"] if not feature.startswith("winner_")}


def cluster_union_features(cluster: list[dict[str, Any]]) -> set[str]:
    union: set[str] = set()
    for record in cluster:
        union.update(clustering_features(record))
    return union


def jaccard(left: set[str], right: set[str]) -> float:
    if not left and not right:
        return 1.0
    intersection = len(left & right)
    union = len(left | right)
    return intersection / union if union else 0.0


def build_cluster(bucket: str, records: list[dict[str, Any]]) -> dict[str, Any]:
    feature_counter: Counter[str] = Counter()
    affected_targets: Counter[str] = Counter()
    winner_counter: Counter[str] = Counter()
    winner_signature_counter: Counter[str] = Counter()
    pairwise_scores: list[float] = []

    for index, record in enumerate(records):
        feature_counter.update(clustering_features(record))
        affected_targets.update(target["label"] for target in record["affectedTargets"])
        winner_family = record["winnerSummary"].get("family")
        winner_signature = record["winnerSummary"].get("signatureHash")
        if winner_family:
            winner_counter[winner_family] += 1
        if winner_signature:
            winner_signature_counter[winner_signature] += 1
        for other in records[index + 1 :]:
            pairwise_scores.append(jaccard(clustering_features(record), clustering_features(other)))

    support_count = len(records)
    dominant_signal_threshold = max(1, (support_count + 1) // 2)
    dominant_signals = dominant_values(
        (
            feature
            for feature, count in sorted(feature_counter.items(), key=lambda item: (-item[1], item[0]))
            if count >= dominant_signal_threshold
        )
    )
    cluster_fingerprint_hash = stable_hash(bucket + "|" + "|".join(dominant_signals or sorted(feature_counter)[:8]))[:16]
    average_similarity = sum(pairwise_scores) / len(pairwise_scores) if pairwise_scores else 1.0
    confidence = round(min(0.99, 0.45 + support_count * 0.1 + average_similarity * 0.25), 2)
    stable_winner_families = [
        {"family": family, "supportCount": count}
        for family, count in sorted(winner_counter.items(), key=lambda item: (-item[1], item[0]))
        if count >= max(2, support_count // 2)
    ]
    mined_signatures = build_mined_signatures(
        bucket=bucket,
        dominant_signals=dominant_signals,
        support_count=support_count,
        confidence=confidence,
    )
    return {
        "id": f"device_cluster_{bucket}_{cluster_fingerprint_hash}",
        "bucket": bucket,
        "clusterFingerprintHash": cluster_fingerprint_hash,
        "supportCount": support_count,
        "dominantSignals": dominant_signals[:8],
        "representativeTargets": [
            label
            for label, _count in sorted(affected_targets.items(), key=lambda item: (-item[1], item[0]))[:5]
        ],
        "stableWinnerFamilies": stable_winner_families,
        "stableWinnerSignatureHashes": [
            signature
            for signature, count in sorted(winner_signature_counter.items(), key=lambda item: (-item[1], item[0]))
            if count >= max(2, support_count // 2)
        ][:5],
        "confidence": confidence,
        "novelty": "unknown",
        "driftState": "unknown",
        "records": [record["recordId"] for record in records],
        "minedSignatures": mined_signatures,
    }


def build_mined_signatures(
    bucket: str,
    dominant_signals: list[str],
    support_count: int,
    confidence: float,
) -> list[dict[str, Any]]:
    if support_count < 2:
        return []
    signatures: list[dict[str, Any]] = []
    families = [
        ("dns_tampering_signature", "dns_", "DNS tampering"),
        ("http_redirect_signature", "http_", "HTTP redirect"),
        ("tls_failure_signature", "tls_", "TLS failure"),
        ("trigger_sensitive_signature", "trigger:", "Trigger-sensitive"),
        ("route_sensitive_signature", "route_", "Route-sensitive"),
    ]
    matched = set()
    for family_id, prefix, label in families:
        matching = [signal for signal in dominant_signals if signal.startswith(prefix)]
        if not matching:
            continue
        signature_hash = stable_hash(bucket + family_id + "|".join(matching[:6]))[:12]
        signatures.append(
            {
                "id": f"{family_id}_{signature_hash}",
                "family": family_id,
                "label": label,
                "supportCount": support_count,
                "confidence": confidence,
                "dominantSignals": matching[:6],
            }
        )
        matched.add(family_id)
    if not signatures and dominant_signals:
        signatures.append(
            {
                "id": f"mixed_signature_{stable_hash(bucket + '|'.join(dominant_signals[:4]))[:12]}",
                "family": "mixed_signature",
                "label": f"Mixed {slugify(bucket)} signature",
                "supportCount": support_count,
                "confidence": confidence,
                "dominantSignals": dominant_signals[:6],
            }
        )
    return signatures
