## Context

The handover monitor and fingerprint source observe the application default.
Tunnel parameters use the same activeNetwork lookup. The existing
CandidateRelayNetworkEpoch already owns passive INTERNET and NOT_VPN callbacks
and readiness through CandidatePhysicalNetworkObserver.

## Goals / Non-Goals

- Goal: use atomic selected physical evidence across the affected consumers.
- Goal: preserve real path-change detection and exact DNS lease authority.
- Non-goal: change native transports, device settings, DNS leases or providers.

## Decisions

- Extend the existing owner with copied selected capabilities and link data.
  Use its readiness checks and avoid another callback or preference ranking.
- Keep diagnostic event epochs unchanged. Handover compares selected semantic
  evidence, including private DNS, routes and MTU, without unrelated epochs.
- If atomic propagation needs model metadata, add only a transient physical
  semantic token to NetworkFingerprint. Exclude it from canonicalParts,
  summary and serialization. Never reuse directDnsUnderlayGeneration.
- Remove the monitor's duplicate default-network callback and use the existing
  physical change flow. Keep debounce and runtime restart policy unchanged.
- Use the same physical snapshot for currentTunnelNetworkParameters. Preserve
  safe configured defaults when no physical snapshot is ready; never read the
  VPN overlay as fallback evidence.
- New fingerprint and builder authority is unavailable for ambiguous legacy
  multi-network selection. Existing diagnostic token behavior is unchanged.

## Contracts and ownership

- core:service owns the observer, capture, handover and builder consumers.
- core:data:model may carry transient metadata only; storage is unchanged.
- Protocol worker owns implementation and existing affected tests in isolation.
  Coordinator owns task/spec records and physical-device operations. Security
  worker performs independent read-only review. No concurrent shared writers.
- No Rust crate, JNI, wire schema, golden, dependency or lease owner changes.

## Risks / Trade-offs

- Startup evidence can be absent: test incomplete and restored observations.
- Callback races can mix data: copy selected metadata under one owner lock.
- Physical fingerprint consumers change authority: run the complete service
  suite and model tests, including binder, DNS and startup boundaries.
- Stored phone logs do not distinguish physical from overlay changes. Source
  regressions establish the defect; fresh device results establish acceptance.

## Migration Plan

No stored migration or compatibility path is required. Preserve scope hashing
and serialization with tests. Run focused RED regressions, then complete model
and service JVM suites, affected static checks, genuine APK preparation and all
four USB scenarios. Observe hosted gates on the final source before closure.
If a regression remains, correct the source and repeat the affected gate;
never suppress real handover, relax payload checks or change device settings.
