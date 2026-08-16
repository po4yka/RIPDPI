# DGN-1786867116840500: Fix VPN owner-process route observation and evidence

## Objective

Replace the calling-UID `activeNetwork` VPN-existence inference with a
generation-safe observation of the RIPDPI-owned Android VPN, correlate its
installed route families with service lifecycle intent, remove the false Route
warning, and export privacy-safe causal evidence for future support audits.

## Ownership

- Service-observation lane: the Android-type-free receipt/evidence/provider
  contracts under `core/data/model` plus the route-authoritative callback state
  machine and lifecycle publication under `core/service`, with focused service
  tests.
- Projection/UI lane: `core/diagnostics` provider consumption, pure evidence
  and archive projection, plus `app` Route-state mapping and their tests.
- Archive/integration lane: diagnostics serialized models, persistence,
  redacted summaries, exporter/renderer, exact archive fixture family, and
  combined-tree validation. This is the only lane allowed to touch archive
  fixtures or goldens, and only after explicit fixture-family blessing approval.
- No Rust crate, JNI, protobuf, dependency, baseline, locale, signing, release,
  or production configuration ownership is assigned by this change.
- Writers run in isolated worktrees. The service-observation lane lands first;
  projection starts against its provider contract, and archive/integration
  starts only after both reviewed behavior commits are combined.

## Execution

- [x] SVC-1786867116840502 Publish a generation-bound VPN route lifecycle receipt and service-correlated callback observation from builder intent through establish, bridge readiness, fail-closed retention, rebuild, handover, and actual descriptor close, with privacy and lifecycle RED tests #feature !high @item:DGN-1786867116840500

  Owned paths: new Android-type-free receipt/evidence/provider contracts under `core/data/model`, `VpnTunnelSessionProvider.kt`, `VpnTunnelRuntime.kt`, a service-owned VPN callback state machine, route/app policy projection helpers under `core/service`, and focused receipt/runtime/callback tests. Record only route/address/DNS families, categorical app-routing shape, bounded app count, own-package exclusion, lifecycle state, MTU band, and metering. Filter callback ownership where public APIs permit, correlate older-platform observations to the current service receipt, never export raw Android network objects or identifiers, and do not alter the existing ready-only receipt until all consumers have a tested migration.

- [x] DGN-1786867116840501 Consume service-correlated VPN route evidence and fix Route projection with RED tests for self-excluded owner default, non-warning startup convergence, callback loss, stale replacement loss, real absence, route mismatch, validation-only failure, forwarding-only failure, and UI warning behavior #bug !high @item:DGN-1786867116840500

  Owned paths: `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/NetworkPathValidationSource.kt`, `NetworkPathSnapshotProjection.kt`, provider adapters and matching `core/diagnostics` tests, `app/src/main/kotlin/com/poyka/ripdpi/activities/VpnDataPlaneStatus.kt`, `MainStateResolvers.kt`, and focused app tests. Preserve `activeNetwork` only as calling-UID context; Route degradation means installed route-family mismatch, `Checking` is non-warning during bounded convergence, Android validation stays on Network, and native forwarding stays on Tunnel. Run one RED/GREEN/refactor cycle per behavior.

- [ ] DGN-1786867116840503 Add schema-10 `vpnRouteEvidence` archive projection from the service provider; preserve legacy path snapshots and decode, enforce redaction, run combined gates, and record API 36 device evidence #feature !high @item:DGN-1786867116840500

  Owned paths: `NetworkMetadataProvider.kt`, diagnostics context/route assessment models, runtime snapshot persistence, redacted summaries, archive JSON builder/renderer, exact tests and the explicitly authorized schema-10 archive fixture family only. Add optional `vpnRouteEvidence` plus stable reason/source tokens without repurposing legacy `pathSnapshots.vpn`; do not claim arbitrary client-UID routing. Run golden tests without blessing, present an intentional fixture diff for approval if required, and verify self-exclusion plus start/rebuild/handover/stop behavior on the connected API 36 device with third-party traffic correlated to TUN counters.

## Verification

- TDD evidence for every behavior: exact RED failure, minimum GREEN change, and
  refactor rerun. Source-text assertions do not satisfy behavior acceptance.
- Targeted JVM gates:
  - `./gradlew :core:diagnostics:testDebugUnitTest`
  - `./gradlew :core:service:testDebugUnitTest`
  - `./gradlew :app:testGithubFullDebugUnitTest`
- Combined gates:
  - `./gradlew staticAnalysis`
  - `./gradlew :app:assembleGithubFullDebug`
  - `python3 scripts/ci/check_architecture_health.py`
  - `./taskctl validate`
  - `git diff --check`
- If any user-facing string changes, run
  `./gradlew :app:lintGithubFullDebug :core:service:lintDebug` and keep all nine
  locales in the same serialized lane.
- Run the owning archive/golden tests without any bless flag. No
  `RIPDPI_BLESS_GOLDENS=1`, record task, or bless script is authorized by this
  plan.
- Device evidence is separate: API 36 must show calling-UID underlay plus a
  RIPDPI-owned validated VPN callback, matching installed route families, no
  false Route warning, coherent lifecycle generations across start/rebuild/
  handover/stop, and TUN-counter movement from a distinct client UID.
- Obtain independent read-only Kotlin/VPN lifecycle, diagnostics privacy, and
  final combined-diff reviews. Report local, device, hosted CI, artifact, and
  deployment evidence independently; only local planning validation is in scope
  for this proposal turn.
