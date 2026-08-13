## Context

`FailoverCoordinator` treats native relay health as a passive trigger and calls `FailoverEgressProbe` through the active local SOCKS endpoint before switching. `SimpleEgressHealthMemory` already stores network- and proof-scoped failures and `buildCandidates()` already excludes matching entries. The missing link is that a general failed active confirmation is logged but not recorded; only the separate sustained-XUDP branch writes negative evidence.

## Goals / Non-Goals

- Goal: Route every failed active relay confirmation into the existing bounded health-memory contract using the capabilities actually probed.
- Goal: Preserve successful-probe recovery and the current failover debounce/switch ordering.
- Non-goal: Change relay credentials, remote deployment, probe endpoints, timeout values, candidate priority, or native relay protocols.
- Non-goal: Introduce a new persisted schema or retain endpoints, hostnames, credentials, or raw network identifiers.

## Decisions

- Record negative evidence inside `confirmRelayEgress`, immediately after a failed probe result. This function owns the active candidate and the effective `probeRequirements`, so it can persist the exact proof without duplicating capability logic.
- Use `EgressProof.from(probeRequirements)`. A TCP-only relay in a TCP+UDP session is quarantined only for `tcp_only`; untested UDP capability is not inferred.
- Remove the XUDP-specific duplicate write from `onTelemetryUpdate`; the failed active probe now provides the single authoritative persistence path for both TCP-only and TCP+UDP confirmations.
- Keep the existing 15-minute cooldown and hashed network key. A shorter retry is not justified by the evidence, and a permanent disable would fail closed too aggressively after transient network changes.
- Reject changing retry or debounce timing: it would reduce delay in one run but would not prevent the confirmed-bad profile from re-entering future initial races.

## Contracts and ownership

- Kotlin owner: `app/src/simple/kotlin/com/poyka/ripdpi/failover/FailoverCoordinator.kt`.
- Test owner: `app/src/testGithubSimple/kotlin/com/poyka/ripdpi/failover/FailoverCoordinatorTest.kt`.
- Persistence contract: existing `SimpleEgressHealthMemory.recordConfirmedFailure`; no key format or migration change.
- Rust crates, JNI, protobuf, diagnostics wire contracts, locale resources, dependency manifests, and serialized shared high-risk files are unaffected.

## Risks / Trade-offs

- Transient probe failure could temporarily quarantine a usable profile → retain the bounded cooldown, network scope, and requirement-specific proof.
- Recording before the failover switch could survive a canceled switch → this is intentional because the active probe already confirmed the profile failure independently of whether replacement startup completes.
- Duplicate writes from sustained XUDP handling could obscure ownership → consolidate into the active-probe result path and cover one recorded failure in the regression test.

## Migration Plan

No data migration or compatibility break is required. Existing negative-cache records remain valid and expire under the current TTL. Rollback restores the prior coordinator behavior without changing stored key interpretation. Validation consists of an observed RED/GREEN JVM regression, the full GitHub Simple app unit suite, `staticAnalysis`, OpenSpec strict validation, task validation, and architecture health. Hosted CI is reported separately after push; physical-device and live relay deployment proof are not claimed by this client-side change.
