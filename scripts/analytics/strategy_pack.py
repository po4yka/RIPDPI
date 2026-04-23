from __future__ import annotations

import json
from typing import Any

from .common import (
    DEFAULT_BASELINE_STRATEGY_PACK_CATALOG,
    load_json,
    now_iso_utc,
    slugify,
)


def build_candidate_strategy_pack_catalog(
    *,
    extracted_payload: dict[str, Any],
    device_catalog: dict[str, Any],
    winner_catalog: dict[str, Any],
    corpus_name: str,
    baseline_catalog_path=DEFAULT_BASELINE_STRATEGY_PACK_CATALOG,
) -> dict[str, Any]:
    baseline_catalog = load_json(baseline_catalog_path)
    record_lookup = {
        record["recordId"]: record
        for record in extracted_payload.get("records", [])
    }
    cluster_lookup = {
        cluster["clusterFingerprintHash"]: cluster
        for cluster in device_catalog.get("clusters", [])
        if cluster.get("clusterFingerprintHash")
    }
    generated_host_lists: list[dict[str, Any]] = []
    generated_packs: list[dict[str, Any]] = []
    default_pack = baseline_catalog.get("packs", [{}])[0] if baseline_catalog.get("packs") else {}
    default_tls_profile_set_id = select_default_tls_profile_set_id(baseline_catalog)
    default_morph_policy_id = (
        default_pack.get("morphPolicyId")
        or first_id(baseline_catalog.get("morphPolicies", []))
    )
    default_transport_module_ids = list(default_pack.get("transportModuleIds", []))
    default_feature_flag_ids = list(default_pack.get("featureFlagIds", []))

    for mapping in winner_catalog.get("mappings", []):
        cluster = cluster_lookup.get(mapping.get("clusterFingerprintHash"))
        if cluster is None:
            continue
        record = representative_record_for_cluster(cluster, record_lookup)
        if record is None:
            continue
        generated = build_generated_pack(
            mapping=mapping,
            cluster=cluster,
            record=record,
            corpus_name=corpus_name,
            default_tls_profile_set_id=default_tls_profile_set_id,
            default_morph_policy_id=default_morph_policy_id,
            default_transport_module_ids=default_transport_module_ids,
            default_feature_flag_ids=default_feature_flag_ids,
        )
        if generated["hostList"] is not None:
            generated_host_lists.append(generated["hostList"])
        generated_packs.append(generated["pack"])

    notes_suffix = (
        f" Offline learner candidate generated from corpus '{corpus_name}' "
        f"with {len(generated_packs)} generated pack(s); review before signing."
    )
    return {
        "schemaVersion": baseline_catalog.get("schemaVersion", 3),
        "generatedAt": now_iso_utc(),
        "sequence": int(baseline_catalog.get("sequence", 0)) + 1,
        "issuedAt": now_iso_utc(),
        "channel": baseline_catalog.get("channel", "stable"),
        "minAppVersion": baseline_catalog.get("minAppVersion", "0.0.0"),
        "minNativeVersion": baseline_catalog.get("minNativeVersion", "0.0.0"),
        "notes": (baseline_catalog.get("notes", "").rstrip() + notes_suffix).strip(),
        "packs": list(baseline_catalog.get("packs", [])) + generated_packs,
        "tlsProfiles": list(baseline_catalog.get("tlsProfiles", [])),
        "morphPolicies": list(baseline_catalog.get("morphPolicies", [])),
        "hostLists": list(baseline_catalog.get("hostLists", [])) + generated_host_lists,
        "transportModules": list(baseline_catalog.get("transportModules", [])),
        "featureFlags": list(baseline_catalog.get("featureFlags", [])),
        "rollout": dict(baseline_catalog.get("rollout", {})),
    }


def build_generated_pack(
    *,
    mapping: dict[str, Any],
    cluster: dict[str, Any],
    record: dict[str, Any],
    corpus_name: str,
    default_tls_profile_set_id: str,
    default_morph_policy_id: str,
    default_transport_module_ids: list[str],
    default_feature_flag_ids: list[str],
) -> dict[str, Any]:
    fingerprint_hash = mapping["clusterFingerprintHash"]
    pack_id = f"offline-{slugify(cluster['bucket'])}-{fingerprint_hash[:12]}"
    strategy_id = f"winner-{fingerprint_hash[:12]}"
    signature = record["winnerSummary"].get("signature") or {}
    candidate_ids = build_candidate_ids(signature)
    bucket_label = cluster["bucket"].replace("_", " ").title()
    host_list = build_generated_host_list(pack_id=pack_id, cluster=cluster)
    tls_profile_set_id = choose_tls_profile_set_id(
        default_tls_profile_set_id=default_tls_profile_set_id,
        signature=signature,
    )
    recommended_proxy_config = {
        "strategyPackId": pack_id,
        "strategy": strategy_id,
        "signatureHash": mapping["recommendedWinnerSignatureHash"],
        "tcpStrategyFamily": signature.get("tcpStrategyFamily"),
        "quicStrategyFamily": signature.get("quicStrategyFamily"),
        "dnsStrategyFamily": signature.get("dnsStrategyFamily"),
        "desyncMethod": signature.get("desyncMethod"),
    }
    pack = {
        "id": pack_id,
        "version": f"offline.2026.04.{fingerprint_hash[:8]}",
        "title": f"Offline {bucket_label}",
        "description": (
            f"Offline learner candidate for {bucket_label.lower()} conditions "
            f"derived from reviewed diagnostics archives."
        ),
        "notes": (
            f"Derived from corpus '{corpus_name}' with reproducibility "
            f"{mapping['reproducibilityScore']} across {mapping['sourceArchiveCount']} archive(s)."
        ),
        "triggerMetadata": build_trigger_metadata(cluster),
        "hostListRefs": [host_list["id"]] if host_list is not None else [],
        "tlsProfileSetId": tls_profile_set_id,
        "morphPolicyId": default_morph_policy_id,
        "transportModuleIds": default_transport_module_ids,
        "featureFlagIds": default_feature_flag_ids,
        "rollout": {
            "cohort": "offline_analytics",
            "percentage": 0,
            "staged": True,
        },
        "strategies": [
            {
                "id": strategy_id,
                "label": mapping["recommendedWinnerFamily"],
                "recommendedProxyConfigJson": json.dumps(
                    {key: value for key, value in recommended_proxy_config.items() if value is not None},
                    separators=(",", ":"),
                    ensure_ascii=True,
                    sort_keys=True,
                ),
                "candidateIds": candidate_ids,
                "notes": (
                    f"Generated from cluster {cluster['id']} "
                    f"(support={mapping['supportCount']}, "
                    f"negativeEvidence={mapping['negativeEvidenceCount']})."
                ),
            }
        ],
    }
    return {
        "pack": pack,
        "hostList": host_list,
    }


def representative_record_for_cluster(
    cluster: dict[str, Any],
    record_lookup: dict[str, dict[str, Any]],
) -> dict[str, Any] | None:
    winner_signature_hashes = set(cluster.get("stableWinnerSignatureHashes", []))
    for record_id in cluster.get("records", []):
        record = record_lookup.get(record_id)
        if record is None:
            continue
        signature_hash = record.get("winnerSummary", {}).get("signatureHash")
        if signature_hash and signature_hash in winner_signature_hashes:
            return record
    for record_id in cluster.get("records", []):
        record = record_lookup.get(record_id)
        if record is not None:
            return record
    return None


def build_candidate_ids(signature: dict[str, Any]) -> list[str]:
    candidate_ids: list[str] = []
    for value in (
        signature.get("desyncMethod"),
        signature.get("tcpStrategyFamily"),
        signature.get("quicStrategyFamily"),
        signature.get("dnsStrategyFamily"),
    ):
        if isinstance(value, str) and value and value not in candidate_ids:
            candidate_ids.append(value)
    return candidate_ids


def build_trigger_metadata(cluster: dict[str, Any]) -> list[str]:
    metadata = [
        f"offline_cluster:{cluster['clusterFingerprintHash']}",
        f"offline_bucket:{cluster['bucket']}",
    ]
    metadata.extend(
        f"offline_signature:{signature['id']}"
        for signature in cluster.get("minedSignatures", [])[:2]
        if signature.get("id")
    )
    metadata.extend(
        f"offline_signal:{slugify(signal)}"
        for signal in cluster.get("dominantSignals", [])[:2]
        if signal
    )
    return metadata


def build_generated_host_list(
    *,
    pack_id: str,
    cluster: dict[str, Any],
) -> dict[str, Any] | None:
    hosts = [
        target
        for target in cluster.get("representativeTargets", [])
        if is_domain_like_target(target)
    ][:5]
    if not hosts:
        return None
    return {
        "id": f"{pack_id}-targets",
        "title": f"{cluster['bucket'].replace('_', ' ').title()} representative targets",
        "description": "Offline-analytics representative targets for analyst review.",
        "hosts": hosts,
    }


def is_domain_like_target(value: str) -> bool:
    return "." in value and " " not in value and ":" not in value


def choose_tls_profile_set_id(
    *,
    default_tls_profile_set_id: str,
    signature: dict[str, Any],
) -> str:
    tcp_family = str(signature.get("tcpStrategyFamily") or "")
    quic_family = str(signature.get("quicStrategyFamily") or "")
    if "ech" in tcp_family or "ech" in quic_family:
        return "ech_canary_v1"
    return default_tls_profile_set_id


def select_default_tls_profile_set_id(baseline_catalog: dict[str, Any]) -> str:
    default_pack = baseline_catalog.get("packs", [{}])[0] if baseline_catalog.get("packs") else {}
    return (
        default_pack.get("tlsProfileSetId")
        or first_id(baseline_catalog.get("tlsProfiles", []))
        or ""
    )


def first_id(items: list[dict[str, Any]]) -> str:
    for item in items:
        value = item.get("id")
        if isinstance(value, str) and value:
            return value
    return ""
