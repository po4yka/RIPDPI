# ADR 0018: Remembered network policies as input to shared priors

> Status: Accepted (conditional go). Decision date: 2026-11-27. Task: DAT-1795705030545238.

## Context

A remembered network policy records that a route or strategy was validated or suppressed on one network. The Offline Learner can start from shared priors: a signed bundle of per-strategy success priors. This study asks whether remembered policies can feed those priors as aggregated, opt-in data, and under which privacy limits. It is research only. It changes no source.

The opt-in anonymous field report is the only allowed channel for data that leaves the device. Its rules apply here: opt-in, no user identification, no addresses of user networks, and buckets below the report minimum are suppressed.

## What a remembered policy contains

Source: `RememberedNetworkPolicyEntity` in `core/diagnostics-data/.../DiagnosticsPolicyEntities.kt`, status constants in `core/data/model/.../NetworkStrategyMemory.kt`.

| Field | Content | Privacy class |
| --- | --- | --- |
| `fingerprintHash` | SHA-256 of `NetworkFingerprint.scopeKey()`. For Wi-Fi the hash input includes SSID, BSSID and gateway; for cellular it includes operator codes and carrier ID; DNS server addresses are in every key. | Stable pseudonymous network identifier. The input space is small enough to brute-force for a known network. |
| `summaryJson` | `NetworkFingerprintSummary`: transport, network state, identity kind, private-DNS mode, DNS server count. | Coarse, but adds no value to a prior. |
| `proxyConfigJson` | The full proxy and desync configuration that was applied. | Can hold endpoints and credentials. |
| `vpnDnsPolicyJson` | DNS policy, including resolver choice. | Can hold resolver addresses. |
| `strategySignatureJson` | The applied strategy signature. | Exact evasion parameters. |
| `winningTcpStrategyFamily`, `winningQuicStrategyFamily` | Strategy family names. | Coarse enum values. |
| `connectionConcurrencyPolicyJson` | Local connection limits. | No value to a prior. |
| `mode`, `source` | Runtime mode and how the policy was learned. | Coarse enum values. |
| `status` | `validated` or `suppressed`. | Coarse outcome. |
| `successCount`, `failureCount`, `consecutiveFailureCount` | Outcome counters. | Counts. |
| `firstObservedAt`, `lastValidatedAt`, `lastAppliedAt`, `suppressedUntil`, `updatedAt` | Exact instants. | Linkable timing. |

## What the shared priors consume

Source: `ripdpi-shared-priors` (`manifest.rs`, `parser.rs`) and `ripdpi-runtime-strategy` (`strategy_evolver/shared_priors.rs`, `selection.rs`).

- The bundle is a signed manifest plus NDJSON records of `{combo_hash, alpha, beta}`.
- `combo_hash` is `canonical_combo_hash(StrategyCombo)`, a stable hash over the evolver's strategy dimensions.
- The priors are global. A record has no network, region or access-type key.
- Selection caps a prior at 8 pseudo-observations, so local evidence takes over quickly. This also bounds the effect of a poisoned prior.
- The release channel stays off until the owner provisions a signing key and release URLs. That infrastructure is not complete.

The crate also holds `CoarseKey`, `CoarsePayload` and `OptInUploader`. They define a coarse contribution shape and an opt-in gate, but they have no transport and no caller outside the crate.

## Fields an aggregate may carry

An aggregate is a sum over policies on one device for one report hour. It never holds a row per policy or per network.

- `combo_hash`, computed on the device from the policy's strategy through `canonical_combo_hash`. This needs a defined mapping from a stored strategy signature to a `StrategyCombo`; no such mapping exists today.
- Outcome class: `validated` or `suppressed`.
- `successes` and `failures`: the sums of `successCount` and `failureCount` over the policies in the bucket.
- The network context that the field report already sends for the hour: the `network` object (`source`, `network_type`, and either MCC and MNC, or hint ASN and country, or neither).
- The hour start and the app version (`major.minor`) that the field report already sends.

## Fields an aggregate must never carry

- `fingerprintHash`, or any other hash, prefix or derivative of `scopeKey()`. A stable network pseudonym links reports over time and can be reversed for a known network.
- SSID, BSSID, gateway, DNS server addresses, operator or carrier identifiers beyond the MCC and MNC that the field report contract already permits, and any device or account identifier.
- `proxyConfigJson`, `vpnDnsPolicyJson` and `connectionConcurrencyPolicyJson`, in any form.
- `strategySignatureJson` or any raw strategy parameter. Only the `combo_hash` leaves the device.
- Exact timestamps (`firstObservedAt`, `lastValidatedAt`, `lastAppliedAt`, `suppressedUntil`, `updatedAt`) and the per-policy `id`.
- The number of remembered networks on the device, and any per-network count.

## Fit with the field report and the shared priors manifest

Field report: the aggregate fits as a new optional section of the existing `client-field-report` payload. It does not get its own channel or endpoint, and the dormant `OptInUploader` path must not become a second channel. It reuses everything the field report already enforces:

- Consent: `field_report_enabled`, off by default, cleared by a backup restore; turning it off deletes unsent data.
- Transport: the subscription's report endpoint, through the active tunnel, within `max_reports_per_day`.
- Context: the hour's `network` object, so no new network attribute is added.
- Suppression: a bucket below `MinBucketAttempts` (5) in `successes + failures` is dropped on the device before upload, the same rule the relay buckets follow.

This is a contract change: a new payload schema version, the matching server-side change in the deployment repository, and an updated consent text in every locale.

Shared priors manifest: the global form fits manifest version 1 without change. The server sums validated successes into `alpha` and failures into `beta` per `combo_hash`, applies its own cross-device minimum, and signs the result with the existing release process. A context-keyed prior (for example per ASN or access type) does not fit version 1, because a record has no context key. That needs a manifest version 2 and a separate decision.

## Risks

- Double counting: the policy counters and the evolver's local statistics come from the same sessions. The aggregate must take one of them, not both.
- Poisoning: a cohort token can send false outcomes. The per-day report cap, the server-side minimum and the 8 pseudo-observation cap bound the effect, but the server should also cap the weight of one cohort.
- Small populations: a rare combo in a small region can be rare enough to single out a user even above 5 attempts. The server-side minimum must count distinct reports, not only attempts.

## Decision

Conditional go. Remembered policies may feed the shared priors only as the aggregate above, inside the opt-in field report, and only when all of these hold:

1. The shared priors release infrastructure is complete: signing key provisioned, release URLs set, and a signed bundle published and applied on a test device.
2. A native mapping from a stored strategy signature to `StrategyCombo` exists and is tested, so the device computes `combo_hash` itself.
3. The field report schema gets a new version with the optional policy section, and the server in the deployment repository accepts it and applies a cross-device minimum on distinct reports.
4. The consent text and the user-visible privacy documentation name the new data in all locales.
5. The aggregate and the evolver statistics are not both counted for the same sessions.

Until condition 1 holds, this stays research. No implementation task is opened from this ADR. A context-keyed prior needs its own decision.
