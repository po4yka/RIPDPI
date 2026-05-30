from __future__ import annotations

import random
from typing import Any

from .common import (
    FEATURE_SCHEMA_VERSION,
    OFFLINE_RECORD_SCHEMA_VERSION,
    now_iso_utc,
    slugify,
    stable_hash,
)

# Number of synthetic affected targets attached to each simulated record.
SIM_TARGET_COUNT = 3


class CensorScenario:
    """A named, CensorLab-style censorship scenario.

    The block model names the surviving strategy-arm winner family (the arm the
    simulated middlebox fails to defeat) plus the signature fields that downstream
    winner-mapping consumes.  Families and signature fields mirror the real
    strings the rest of the pipeline already emits for the sample corpus, so the
    simulated payload flows through cluster/publish exactly like a field-derived
    one.
    """

    def __init__(
        self,
        *,
        scenario_id: str,
        label: str,
        assessment_code: str,
        observed_features: list[str],
        winner_family: str | None,
        signature: dict[str, Any] | None,
    ) -> None:
        self.scenario_id = scenario_id
        self.label = label
        self.assessment_code = assessment_code
        # Sorted to match the extractor, which emits sorted observedFeatures.
        self.observed_features = sorted(set(observed_features))
        self.winner_family = winner_family
        self.signature = signature


# Built-in scenario registry.  Each scenario survives via a winner family that
# the real pipeline already recognises (tlsrec_split + quic_sni_split,
# disorder_host + quic_disabled), so generated packs reference known arm names.
_SCENARIOS: dict[str, CensorScenario] = {
    "sni_block": CensorScenario(
        scenario_id="sni_block",
        label="SNI keyword block on TLS ClientHello",
        assessment_code="raw_network_selective_blocking",
        observed_features=[
            "assessment:raw_network_selective_blocking",
            "tls_outcome:tls_version_split",
            "tls_signal:sni_reset",
            "tls_error:reset",
        ],
        winner_family="tlsrec_split + quic_sni_split",
        signature={
            "mode": "VPN",
            "configSource": "simulator",
            "hostAutolearn": "enabled",
            "desyncMethod": "tlsrec_split",
            "chainSummary": "tcp: tlsrec(split)",
            "tcpStrategyFamily": "tlsrec_split",
            "quicStrategyFamily": "quic_sni_split",
            "dnsStrategyFamily": "dns_encrypted_doh",
            "dnsStrategyLabel": "Simulated DoH",
            "protocolToggles": ["HTTPS", "UDP"],
        },
    ),
    "quic_udp443_block": CensorScenario(
        scenario_id="quic_udp443_block",
        label="QUIC UDP/443 throttle and block",
        assessment_code="raw_network_selective_blocking",
        observed_features=[
            "assessment:raw_network_selective_blocking",
            "tls_outcome:tls_version_split",
            "tls_signal:quic_initial_drop",
        ],
        winner_family="disorder_host + quic_disabled",
        signature={
            "mode": "VPN",
            "configSource": "simulator",
            "hostAutolearn": "enabled",
            "desyncMethod": "disorder_host",
            "chainSummary": "tcp: disorder(host)",
            "tcpStrategyFamily": "disorder_host",
            "quicStrategyFamily": "quic_disabled",
            "dnsStrategyFamily": "dns_encrypted_doh",
            "dnsStrategyLabel": "Simulated DoH",
            "protocolToggles": ["HTTPS"],
        },
    ),
    "rst_on_keyword": CensorScenario(
        scenario_id="rst_on_keyword",
        label="TCP RST injection on keyword match",
        assessment_code="raw_network_selective_blocking",
        observed_features=[
            "assessment:raw_network_selective_blocking",
            "tls_outcome:tls_version_split",
            "tls_error:reset",
            "http_status:http_status_403",
        ],
        winner_family="disorder_host + quic_disabled",
        signature={
            "mode": "VPN",
            "configSource": "simulator",
            "hostAutolearn": "enabled",
            "desyncMethod": "disorder_host",
            "chainSummary": "tcp: disorder(host)",
            "tcpStrategyFamily": "disorder_host",
            "quicStrategyFamily": "quic_disabled",
            "dnsStrategyFamily": "dns_encrypted_doh",
            "dnsStrategyLabel": "Simulated DoH",
            "protocolToggles": ["HTTP", "HTTPS"],
        },
    ),
    "dns_poison": CensorScenario(
        scenario_id="dns_poison",
        label="DNS poisoning / resolver injection",
        assessment_code="resolver_interference",
        observed_features=[
            "assessment:resolver_interference",
            "dns_injection:suspected",
            "dns_outcome:dns_nxdomain_mismatch",
            "dns_signal:rcode_mismatch",
        ],
        winner_family="tlsrec_split + quic_sni_split",
        signature={
            "mode": "VPN",
            "configSource": "simulator",
            "hostAutolearn": "enabled",
            "desyncMethod": "tlsrec_split",
            "chainSummary": "tcp: tlsrec(split)",
            "tcpStrategyFamily": "tlsrec_split",
            "quicStrategyFamily": "quic_sni_split",
            "dnsStrategyFamily": "dns_encrypted_doh",
            "dnsStrategyLabel": "Simulated DoH",
            "protocolToggles": ["HTTPS", "UDP"],
        },
    ),
    "tls_split": CensorScenario(
        scenario_id="tls_split",
        label="TLS record fragmentation / version split",
        assessment_code="raw_network_selective_blocking",
        observed_features=[
            "assessment:raw_network_selective_blocking",
            "tls_outcome:tls_version_split",
            "tls_signal:record_split",
        ],
        winner_family="tlsrec_split + quic_sni_split",
        signature={
            "mode": "VPN",
            "configSource": "simulator",
            "hostAutolearn": "enabled",
            "desyncMethod": "tlsrec_split",
            "chainSummary": "tcp: tlsrec(split)",
            "tcpStrategyFamily": "tlsrec_split",
            "quicStrategyFamily": "quic_sni_split",
            "dnsStrategyFamily": "dns_encrypted_doh",
            "dnsStrategyLabel": "Simulated DoH",
            "protocolToggles": ["HTTPS", "UDP"],
        },
    ),
}


def scenario_ids() -> list[str]:
    """All built-in scenario ids, in registry order."""
    return list(_SCENARIOS.keys())


def get_scenario(scenario_id: str) -> CensorScenario:
    scenario = _SCENARIOS.get(scenario_id)
    if scenario is None:
        known = ", ".join(scenario_ids())
        raise KeyError(f"Unknown censor scenario '{scenario_id}'. Known scenarios: {known}")
    return scenario


def simulate_corpus(scenario_ids: list[str], seed: int, count: int) -> dict[str, Any]:
    """Synthesize an extracted-records-shaped payload from named scenarios.

    The shape mirrors :func:`scripts.analytics.extract.run_extract` exactly so the
    payload can be fed straight into ``run_cluster`` / ``publish_outputs``.  All
    per-record variation comes from ``random.Random(seed)`` only; ``recordId`` is
    deterministic from ``(scenario_id, seed, index)``.
    """
    if count < 1:
        raise ValueError("count must be at least 1")
    rng = random.Random(seed)
    records: list[dict[str, Any]] = []
    for scenario_id in scenario_ids:
        scenario = get_scenario(scenario_id)
        for index in range(count):
            records.append(_simulate_record(scenario=scenario, seed=seed, index=index, rng=rng))
    return {
        "schemaVersion": OFFLINE_RECORD_SCHEMA_VERSION,
        "featureSchemaVersion": FEATURE_SCHEMA_VERSION,
        "generatedAt": now_iso_utc(),
        "recordCount": len(records),
        "records": records,
    }


def _simulate_record(
    *,
    scenario: CensorScenario,
    seed: int,
    index: int,
    rng: random.Random,
) -> dict[str, Any]:
    record_id = _record_id(scenario.scenario_id, seed, index)
    confidence = round(0.6 + rng.random() * 0.39, 2)
    affected_targets = _affected_targets(scenario=scenario, rng=rng)
    winner_summary = _winner_summary(scenario)
    return {
        "schemaVersion": OFFLINE_RECORD_SCHEMA_VERSION,
        "featureSchemaVersion": FEATURE_SCHEMA_VERSION,
        "recordId": record_id,
        "sourcePath": f"simulated/{scenario.scenario_id}",
        "runType": "simulated_scenario",
        "generatedAt": now_iso_utc(),
        "networkScopeKey": stable_hash(record_id)[:32],
        "connectivityAssessment": {
            "code": scenario.assessment_code,
            "confidence": confidence,
            "summary": (
                f"Simulated scenario '{scenario.label}'. "
                f"{len(affected_targets)} affected targets recorded."
            ),
        },
        "affectedTargets": affected_targets,
        "winnerSummary": winner_summary,
        "observedFeatures": _observed_features(scenario),
    }


def _record_id(scenario_id: str, seed: int, index: int) -> str:
    return f"sim-{slugify(scenario_id)}-{seed}-{index}"


def _affected_targets(*, scenario: CensorScenario, rng: random.Random) -> list[dict[str, Any]]:
    targets: list[dict[str, Any]] = []
    for ordinal in range(SIM_TARGET_COUNT):
        # Synthetic RFC 2606 .example hostnames only — never real domains.
        label = f"sim-target-{ordinal + 1}.example"
        count = rng.randint(1, 3)
        targets.append(
            {
                "label": label,
                "category": "domain",
                "outcomes": [{"value": "blocked", "count": count}],
                "stages": [{"value": "primary", "count": count}],
            }
        )
    return sorted(targets, key=lambda target: target["label"])


def _winner_summary(scenario: CensorScenario) -> dict[str, Any]:
    if scenario.winner_family is None or scenario.signature is None:
        return {
            "family": None,
            "tcpFamily": None,
            "quicFamily": None,
            "signatureHash": None,
            "signature": None,
        }
    signature = scenario.signature
    signature_hash = stable_hash(_json_like(signature))[:16]
    return {
        "family": scenario.winner_family,
        "tcpFamily": signature.get("tcpStrategyFamily"),
        "quicFamily": signature.get("quicStrategyFamily"),
        "signatureHash": signature_hash,
        "signature": dict(signature),
    }


def _observed_features(scenario: CensorScenario) -> list[str]:
    features = set(scenario.observed_features)
    if scenario.winner_family:
        features.add(f"winner_family:{slugify(scenario.winner_family)}")
    return sorted(features)


def _json_like(value: Any) -> str:
    """Stable serialization mirroring extract.json_like for signature hashing."""
    if value is None:
        return ""
    if isinstance(value, dict):
        return "".join(f"{key}:{_json_like(value[key])};" for key in sorted(value))
    if isinstance(value, list):
        return "[" + ",".join(_json_like(item) for item in value) + "]"
    return str(value)
