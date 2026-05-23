from __future__ import annotations

from bisect import insort
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

CLUSTER_MATCH_THRESHOLD = 0.45
MAX_PAIRWISE_SIMILARITY_COMPARISONS = 4_096
MAX_GREEDY_FEATURE_POSTINGS = 1_024
MAX_GREEDY_CLUSTER_CANDIDATES = 256


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
    cluster_feature_unions: list[set[str]] = []
    feature_cluster_index: dict[str, list[int]] = defaultdict(list)
    empty_feature_cluster_index: int | None = None
    for record in records:
        record_features = clustering_features(record)
        if not record_features:
            if empty_feature_cluster_index is None:
                empty_feature_cluster_index = len(clusters)
                clusters.append([record])
                cluster_feature_unions.append(set())
            else:
                clusters[empty_feature_cluster_index].append(record)
            continue

        best_index = best_matching_cluster_index(
            record_features=record_features,
            cluster_feature_unions=cluster_feature_unions,
            feature_cluster_index=feature_cluster_index,
        )
        if best_index is not None:
            clusters[best_index].append(record)
            new_features = record_features - cluster_feature_unions[best_index]
            cluster_feature_unions[best_index].update(record_features)
            index_cluster_features(feature_cluster_index, best_index, new_features)
        else:
            cluster_index = len(clusters)
            clusters.append([record])
            cluster_feature_unions.append(set(record_features))
            index_cluster_features(feature_cluster_index, cluster_index, record_features)
    return clusters


def best_matching_cluster_index(
    record_features: set[str],
    cluster_feature_unions: list[set[str]],
    feature_cluster_index: dict[str, list[int]],
) -> int | None:
    best_index = None
    best_score = 0.0
    for index in candidate_cluster_indices(record_features, feature_cluster_index, cluster_feature_unions):
        score = jaccard(record_features, cluster_feature_unions[index])
        if score > best_score:
            best_score = score
            best_index = index
    if best_index is not None and best_score >= CLUSTER_MATCH_THRESHOLD:
        return best_index
    return None


def candidate_cluster_indices(
    record_features: set[str],
    feature_cluster_index: dict[str, list[int]],
    cluster_feature_unions: list[set[str]],
) -> list[int]:
    if not record_features:
        return []

    candidate_overlap_counts: Counter[int] = Counter()
    for _posting_size, _feature, cluster_indices in indexed_feature_postings(record_features, feature_cluster_index):
        for index in bounded_cluster_posting(cluster_indices):
            candidate_overlap_counts[index] += 1

    ranked_candidates = sorted(
        candidate_overlap_counts,
        key=lambda index: (
            -estimated_jaccard(record_features, cluster_feature_unions[index], candidate_overlap_counts[index]),
            index,
        ),
    )
    return sorted(ranked_candidates[:MAX_GREEDY_CLUSTER_CANDIDATES])


def indexed_feature_postings(
    record_features: set[str],
    feature_cluster_index: dict[str, list[int]],
) -> list[tuple[int, str, list[int]]]:
    postings = []
    for feature in sorted(record_features):
        cluster_indices = feature_cluster_index.get(feature)
        if cluster_indices:
            postings.append((len(cluster_indices), feature, cluster_indices))
    return sorted(postings, key=lambda item: (item[0], item[1]))


def bounded_cluster_posting(cluster_indices: list[int]) -> list[int]:
    if len(cluster_indices) <= MAX_GREEDY_FEATURE_POSTINGS:
        return cluster_indices
    head_count = MAX_GREEDY_FEATURE_POSTINGS // 2
    tail_count = MAX_GREEDY_FEATURE_POSTINGS - head_count
    return cluster_indices[:head_count] + cluster_indices[-tail_count:]


def estimated_jaccard(record_features: set[str], cluster_features: set[str], overlap_count: int) -> float:
    union = len(record_features) + len(cluster_features) - overlap_count
    return overlap_count / union if union else 0.0


def index_cluster_features(
    feature_cluster_index: dict[str, list[int]],
    cluster_index: int,
    features: set[str],
) -> None:
    for feature in sorted(features):
        insort(feature_cluster_index[feature], cluster_index)


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
    if len(left) > len(right):
        left, right = right, left
    intersection = sum(1 for feature in left if feature in right)
    union = len(left) + len(right) - intersection
    return intersection / union if union else 0.0


def build_cluster(bucket: str, records: list[dict[str, Any]]) -> dict[str, Any]:
    feature_counter: Counter[str] = Counter()
    affected_targets: Counter[str] = Counter()
    winner_counter: Counter[str] = Counter()
    winner_signature_counter: Counter[str] = Counter()
    feature_sets: list[set[str]] = []

    for record in records:
        record_features = clustering_features(record)
        feature_sets.append(record_features)
        feature_counter.update(record_features)
        affected_targets.update(target["label"] for target in record["affectedTargets"])
        winner_family = record["winnerSummary"].get("family")
        winner_signature = record["winnerSummary"].get("signatureHash")
        if winner_family:
            winner_counter[winner_family] += 1
        if winner_signature:
            winner_signature_counter[winner_signature] += 1

    support_count = len(records)
    dominant_signal_threshold = max(1, (support_count + 1) // 2)
    dominant_signals = dominant_values(
        (
            feature
            for feature, count in sorted(feature_counter.items(), key=lambda item: (-item[1], item[0]))
            if count >= dominant_signal_threshold
        )
    )
    fingerprint_source = bucket + "|" + "|".join(dominant_signals or sorted(feature_counter)[:8])
    cluster_fingerprint_hash = stable_hash(fingerprint_source)[:16]
    average_similarity = average_pairwise_similarity(feature_sets)
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


def average_pairwise_similarity(feature_sets: list[set[str]]) -> float:
    record_count = len(feature_sets)
    pair_count = record_count * (record_count - 1) // 2
    if pair_count == 0:
        return 1.0

    total = 0.0
    comparisons = 0
    if pair_count <= MAX_PAIRWISE_SIMILARITY_COMPARISONS:
        for left_index, left in enumerate(feature_sets):
            for right_index in range(left_index + 1, record_count):
                total += jaccard(left, feature_sets[right_index])
                comparisons += 1
    else:
        for ordinal in sampled_pair_ordinals(pair_count):
            left_index, right_index = pair_indices_for_ordinal(record_count, ordinal)
            total += jaccard(feature_sets[left_index], feature_sets[right_index])
            comparisons += 1

    return total / comparisons if comparisons else 1.0


def sampled_pair_ordinals(pair_count: int) -> list[int]:
    sample_count = min(pair_count, MAX_PAIRWISE_SIMILARITY_COMPARISONS)
    if sample_count <= 1:
        return [0]
    last_ordinal = pair_count - 1
    return [
        sample_index * last_ordinal // (sample_count - 1)
        for sample_index in range(sample_count)
    ]


def pair_indices_for_ordinal(record_count: int, ordinal: int) -> tuple[int, int]:
    low = 0
    high = record_count - 2
    while low <= high:
        midpoint = (low + high) // 2
        row_start = pairwise_row_start(record_count, midpoint)
        next_row_start = pairwise_row_start(record_count, midpoint + 1)
        if ordinal < row_start:
            high = midpoint - 1
        elif ordinal >= next_row_start:
            low = midpoint + 1
        else:
            return midpoint, midpoint + 1 + ordinal - row_start
    raise ValueError(f"pair ordinal {ordinal} is out of range for {record_count} records")


def pairwise_row_start(record_count: int, row_index: int) -> int:
    return row_index * (2 * record_count - row_index - 1) // 2


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
