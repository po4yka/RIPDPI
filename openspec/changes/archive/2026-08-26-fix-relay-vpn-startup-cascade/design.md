## Context

The current simple-flavor path has two independent health interpretations. `SimpleInitialRelayRacePolicy` derives its URL from the imported urltest group, while `CapabilityAwareFailoverEgressProbe` uses a hard-coded runtime URL and collapses every OkHttp `IOException` into `tcp_connect`. `FailoverCoordinator` re-probes on each telemetry update while its failure latch remains set. The current code already has generation-aware `DataPlaneEvidenceCollector` counters and serialized restart application, but its active failover path does not consume that positive evidence. Earlier TCP-only session projection, VLESS/REALITY stage telemetry, and `VpnDataPlaneStatus` projection exist in repository history but were removed by a bulk integration commit.

The implementation must preserve `VpnService.protect()` for direct non-loopback relay sockets, keep SOCKS probe traffic on the configured relay path, avoid new shipped diagnostic hosts, and never persist or export raw network/profile secrets.

## Goals / Non-Goals

- Goal: make startup and steady-state relay decisions evidence-based, bounded, generation-scoped, cancel-safe, and observable.
- Goal: restore only reviewed relay/runtime invariants that are still required on the rebased tree.
- Goal: validate the exact integrated simple artifact and `dad-phone` profile on the connected Pixel 7.
- Non-goal: change owner-controlled relay servers, add a backend, add a public probe domain, or redesign the complete transport-selection policy.
- Non-goal: revert the bulk integration commit or overwrite concurrent UI/golden work.

## Decisions

- Add the pure internal contracts to `:core:service`, which both the initial race and the simple-flavor app coordinator can consume:
  - `RelayProbePlan(targetUrl, targetCategory, requirements)`; `targetUrl` is nullable and originates only from the active imported profile.
  - `RelayHealthObservation` containing an opaque attempt ID, hashed profile token, relay kind, capability proof, source, outcome, optional observed stage, target category, timestamp, and data-plane watermark.
  - `RelayHealthDecision` with `Healthy`, `Inconclusive`, or `ConfirmedFailed`, plus the evidence references needed for telemetry.
  - `RelayHealthScope(persistentNetworkHash, sessionGeneration)`; raw network identity is never accepted by this boundary.
- Add a lifecycle-scoped `RelayHealthDecisionEngine`. For VPN mode, a current-generation cross-layer return or increasing upstream application bytes is positive evidence; positive evidence remains recent for 30 seconds and clears pending failure/cooldown. An HTTP status, DNS failure, OkHttp exception, or missing UDP target after local relay readiness is target-specific/inconclusive. Only typed native stages before target exchange (`tcp_connect`, `reality_tls`, `vless_auth`, `vless_request`, `vless_response`) are relay-scoped. Authentication/configuration rejection is permanent; other relay-scoped failures require two observations at least 20 seconds apart with no positive evidence.
- Replace `FailoverEgressProbe`'s Boolean result with the typed observation result and inject the profile-derived `RelayProbePlan`. Remove the hard-coded runtime URL. Unknown OkHttp failures remain inconclusive unless correlated native stage evidence proves a relay failure.
- Implement single-flight probing with one lifecycle-owned `Deferred` per relay tuple. Callers join the in-flight result; within 20 seconds they reuse the last decision instead of issuing another request. Cancellation propagates through the session scope and leaves no global job.
- Keep initial candidate attempts bounded to two application probes. Candidates race in parallel; a non-permanent second attempt starts no earlier than 20 seconds after the first, and the race deadline becomes 45 seconds. A target-only failure ends as typed `verification_inconclusive`, records no negative cooldown, and performs full cleanup. A permanent or twice-observed relay-stage failure becomes `confirmed_failed`. A cached winner remains eligible only under the existing exact persistent scope/signature contract.
- Extend `SimpleEgressHealthMemory` to accept `RelayHealthScope`, clear a tuple on positive evidence, and keep an in-memory negative map keyed by `sessionGeneration` when the persistent network hash is absent. Session entries are cleared on stop/handover; only persistent hashes can enter SharedPreferences. The 15-minute persistent TTL remains unchanged.
- Preserve the existing serialized transport-apply tracker and startup recovery mutex. Before the successor starts, await the old service/session stop receipt and verify that its runtime handle, child job, and listener are gone. Restore the session-local TCP-only preferences behavior; a missing UDP target is `Inconclusive`, never a failed relay.
- Reintroduce `VpnDataPlaneStatus` as a projection rather than extending `AppStatus`: local process readiness remains `AppStatus.Running`, while `Checking`, `Working`, `Unverified`, and `Unavailable` derive from current-generation path validation and relay health evidence. `ServiceStatusReporter` remains the only writer of UI-facing status/telemetry. Rebase the projection onto the current connection-actuator UI lane instead of restoring old UI files wholesale.
- Restore typed relay attempt events by reviewing the relevant pre-regression commits, not cherry-picking them wholesale. The native ring remains bounded/non-blocking and runtime-scoped. Add nullable attempt sequence, stage, outcome, duration, failure stage/class, I/O kind/errno, peer-close phase, and carrier disposition fields. Stage guards emit exactly one terminal event on success, failure, or cancellation; every touched async function receives the required cancel-safety rustdoc and cancellation regression coverage.
- Persist Kotlin health decisions and native stages as ordered diagnostics events. Export only attempt ID, hashed profile token, relay kind, target category, watermark, decision, cooldown scope, and cleanup result. Missing correlation is exported as unavailable. No endpoint, URL host, UUID, SSID/BSSID, credential, or packet payload enters logs, Room, JSONL, or goldens.

## Contracts and ownership

- `:core:service` owns relay health types, classification, lifecycle generation, data-plane watermark projection, cleanup receipts, and status publication.
- `:app` simple source set owns bundle-derived probe planning, candidate policy, single-flight runtime failover integration, and persistent/session cooldown adapters.
- `android-support`, `ripdpi-relay-core`, `ripdpi-vless`, and `ripdpi-relay-android` own native stage emission and the Rust telemetry envelope. Kotlin decoding remains in `:core:engine`.
- `:core:diagnostics-data` and `:core:diagnostics` own nullable event-column migration, persistence, archive JSONL, completeness, and redaction.
- Serialized lanes are exclusive during implementation: native telemetry schema/API snapshot, diagnostics Room schema/export schema, Kotlin/Rust telemetry manifests, and affected goldens. No `Cargo.lock`, dependency, profile-bundle, or locale change is planned unless the rebased contract requires it.

## Risks / Trade-offs

- Longer first connection: the second non-permanent probe can extend startup to 45 seconds. This is preferred to destructive multi-candidate restart cascades; permanent errors still fail immediately.
- Inconclusive target with no cached winner remains fail-closed. It is surfaced distinctly and retriable, rather than poisoning every candidate or claiming VPN success.
- Native stage instrumentation can alter cancellation behavior. Use RAII one-shot guards, avoid blocking mutexes/data-plane logs, run async cancel-safety review, and prove mux reuse remains synchronized after cancellation.
- UI integration overlaps an active local UI lane. Implement service/data contracts first, then rebase and adapt the minimal projection; never restore historical UI files over newer work.
- Schema changes can lose provenance if only one side is updated. Bump each envelope by one from the rebased baseline, update every producer/consumer/manifest together, and add seeded migration plus old/missing/unknown-field codec tests.

## Migration Plan

1. Create RED tests one behavior at a time for classification, positive-evidence suppression, single-flight/rate limits, session cooldown, TCP-only projection, cleanup ordering, status projection, native stages, persistence, and export.
2. Implement Kotlin decision and lifecycle behavior before native schema work; keep every cycle green and run focused module tests.
3. Restore native stage emission as a reviewed patch, bump the relay telemetry envelope, regenerate the Rust API snapshot, and update the strict Kotlin decoder and manifests.
4. Add the next Room migration with nullable/default-safe columns and a seeded previous-version row test; bump the diagnostics archive schema and review only the affected telemetry fixtures/goldens.
5. Rebase onto `origin/main`, resolve the concurrent UI projection semantically, and run combined-tree architecture, static-analysis, Rust metadata/test/fmt/clippy/API-snapshot, diagnostics contract, and simple-flavor gates.
6. Build the exact signed arm64 simple artifact, record source/artifact/bundle hashes without printing secrets, run the approved Pixel 7 matrix within the 15-minute disruption budget, restore the original profile/VPN/reverse-forward state, and observe recovery for 10 minutes.

Rollback is a normal revert of the cohesive implementation commit plus its schema producers/consumers; Room migrations remain forward-only and nullable, so rollback must use a build that understands the migrated database rather than destructive fallback. A device or gate failure leaves the portfolio task and OpenSpec change open.
