# RIPDPI Roadmap

All previously tracked roadmap streams are complete in repo-owned scope. New
incremental hardening work is recorded here as it lands.

## Final Status (2026-04-22)

- Audit roadmap: COMPLETE.
- Architecture refactor: COMPLETE.
- Bypass techniques expansion: COMPLETE in repo-owned scope.
- Bypass modernization: COMPLETE in repo-owned scope.
- Integrations / Track S: COMPLETE in repo-owned scope.

## Incremental Hardening

### 2026-04-22: Strategy-Pack Anti-Rollback

Status: COMPLETE.

- Added signed-catalog anti-rollback metadata via `StrategyPackCatalog.sequence` and `StrategyPackCatalog.issuedAt`.
- Enforced repository-side freshness and monotonic-sequence checks for downloaded catalogs only.
- Added typed refresh failure reporting and runtime state fields for accepted/rejected sequences.
- Added a debug-only local rollback override that bypasses only the monotonic-sequence rejection.
- Updated bundled catalog metadata, operator docs, and regression coverage.

### 2026-04-22: Typed Cache Degradation

Status: COMPLETE.

- Added a shared typed cache-degradation contract for cached control-plane snapshots.
- Converted host-pack and strategy-pack startup loads to return both the active snapshot and any cached-snapshot degradation reason.
- Threaded strategy cache degradation into `StrategyPackRuntimeState` and host-pack cache degradation into settings UI state.
- Added a warning-style host-pack bundled-fallback status when the cached snapshot is unreadable.
- Added load-path regression coverage for unreadable and incompatible cached snapshots.

### 2026-04-23: Native Readiness Events

Status: COMPLETE.

- Added typed native runtime event kinds so readiness and shutdown are keyed off structured `runtime_ready` and `runtime_stopped` events.
- Split Android native event transport into first-class proxy, relay, and WARP rings instead of multiplexing non-proxy events through the proxy ring.
- Emitted explicit readiness events from the embedded proxy, relay, and WARP runtimes only after their listeners were actually bound.
- Switched Kotlin `awaitReady()` wrappers to wait for typed readiness events instead of treating `state == "running"` as sufficient.
- Updated contract fixtures, telemetry goldens, and wrapper/supervisor regression coverage for the stricter readiness handshake.

### 2026-04-23: Typed Telemetry Outcomes

Status: COMPLETE.

- Added a shared typed runtime telemetry outcome contract so polling distinguishes real snapshots, no-data runtime absence, and engine polling errors.
- Switched proxy, relay, WARP, and tunnel telemetry polling to return typed outcomes instead of collapsing failures into `null` or idle snapshots.
- Threaded per-runtime telemetry status through `ServiceTelemetrySnapshot`, diagnostics persistence, exports, and current diagnostics UI surfaces.
- Preserved existing tunnel failure handling while keeping proxy, relay, and WARP polling failures observational only in this pass.
- Bumped the diagnostics Room schema, added the new schema snapshot, and updated telemetry regression fixtures and goldens for the richer status model.

### 2026-04-23: Missing Orchestration Regression Tests

Status: COMPLETE.

- Added deterministic coordinator regressions for delayed handover retries, ignored late handover events after stop, and stale superseded runtime exits across proxy and VPN service modes.
- Hardened proxy, relay, and WARP runtime supervisors so completion callbacks from superseded runtime instances are ignored instead of tearing down replacement sessions.
- Expanded diagnostics bootstrap coverage to lock in one-time automatic-probe subscription wiring and preserve scheduling when runtime-history startup fails.
- Added app-startup bootstrap regressions for subsystem initialization ordering, swallowed detection-observer failures, distinct battery-banner clearing, missing crash-report handling, and continued strategy-pack UI propagation.

### 2026-04-23: Diagnostics Remediation Ladder

Status: COMPLETE.

- Added a shared app-layer remediation ladder model with route-only actions for Advanced Settings, VPN permission, DNS Settings, Diagnostics, and History.
- Replaced the fragmented diagnostics workflow restriction and resolver-action surfaces with a single ladder card inside the scan workflow area.
- Threaded the same ladder model through Home diagnostics and the Home analysis sheet so stale, blocked, and non-actionable outcomes present structured next steps instead of free-form fallback text.
- Added dedicated English and Russian remediation copy plus app/UI regression coverage for Diagnostics and Home ladder mappings.

### 2026-04-23: Strategy-Pack / Host-Pack Health UX

Status: COMPLETE.

- Added a detailed strategy-pack health surface in Advanced Settings with typed status rendering for cache degradation, anti-rollback rejection, verification failures, compatibility rejections, and bundled versus verified-download state.
- Upgraded the existing host-pack status card so typed refresh failures now persist structurally alongside bundled/downloaded and degraded-cache states.
- Introduced a shared host-pack UI-state store plus an idempotent loader/refresh coordinator so Home and Settings consume the same host-pack health state.
- Added an attention-only Home control-plane summary card that groups host-pack and strategy-pack issues behind one Advanced Settings action when either update feed needs review.
- Added English and Russian control-plane copy plus helper/UI regression coverage for the new host-pack, strategy-pack, and Home summary mappings.

### 2026-04-23: Onboarding Chosen-Mode Validation

Status: COMPLETE.

- Replaced the generic onboarding connectivity check with a mode-aware validation flow that starts the selected mode and proves traffic works through that active path.
- Added onboarding-owned notifications and VPN-consent effect handling so validation can request only the prerequisites needed to start the chosen mode.
- Persisted provisional mode and DNS selections during onboarding so reopened onboarding resumes from the real in-progress configuration instead of hardcoded defaults.
- Reworked the final onboarding page to show validation progress, failure recovery with alternate-mode suggestions, and explicit finish actions for keeping the validated mode running or finishing disconnected.
- Added English and Russian onboarding copy plus ViewModel, screen, and effect-handler regression coverage for the new validation and finish paths.

### 2026-04-23: Direct-Mode DNS Classifier

Status: COMPLETE.

- Promoted the existing native `dns_integrity` probe into a typed direct-mode DNS classifier instead of leaving HTTPS/SVCB parsing as unused groundwork.
- Added stable classifier details for five-state DNS classification, answer-class evidence, selected encrypted-resolver role, and HTTPS/ECH support counts on the native diagnostics path.
- Threaded typed DNS classification through the direct-path capability envelope, capability summaries, runtime-context JSON contract, and connection-policy resolution.
- Promoted poisoned DNS results into authority-scoped `DOH_PRIMARY` / `DOH_SECONDARY` policy hints so the later runtime-enforcement slice could consume them directly.
- Added focused Kotlin and Rust regression coverage for the new classifier contract, policy persistence, and runtime-context sanitization.

### 2026-04-23: Honest Direct-Mode Transport Verdicts

Status: COMPLETE.

- Tightened direct-mode verdict derivation so `NO_DIRECT_SOLUTION` now preserves the real transport cause instead of collapsing every all-fail authority into `IP_BLOCK_SUSPECT`.
- Differentiated TLS-blocked, QUIC-blocked, and likely-IP-blocked direct failures in diagnostics while keeping `OWNED_STACK_ONLY` as a separate outcome.
- Added a false-positive guard in the runtime learner so `ALL_IPS_FAILED` must reappear on the next flow before persisting `NO_DIRECT_SOLUTION` / `IP_BLOCK_SUSPECT`.
- Kept tuple-scoped cached policy in place while preventing the new re-verification guard from regressing later positive signals like `QUIC_SUCCESS`.
- Added service and diagnostics regression coverage for the re-verification path and the reason-specific verdict summary text.

### 2026-04-23: DNS And Transport Enforcement Slices

Status: PARTIAL. Core enforcement landed; two follow-up items closed in cleanup epic (P4.2); `DnsPathPreferenceInvalidator.register()` hook-up deferred.

- Promoted the cached direct-path DNS hint from passive metadata into active enforcement: VPN startup can now promote a converged hostname-backed `DOH_PRIMARY` / `DOH_SECONDARY` policy into the active resolver instead of waiting for runtime DNS failures.
- Added authority-scoped encrypted-DNS resolver selection on the native hostname-resolution path so direct-path capability records can steer individual hosts to the intended DoH provider at runtime.
- Gated DoQ on the same transport policy signal: when an authority is not UDP-clean, runtime DNS resolution now automatically downgrades a DoQ context back to DoH for that host.
- Tightened direct-path transport enforcement so `NO_TCP_FALLBACK` no longer leaves the runtime in an inconsistent state where UDP suppression is disabled but the adaptive UDP/QUIC hint layer still behaves as if QUIC is broken for the same authority.
- Added focused Kotlin and Rust regressions for converged VPN DNS promotion, authority-scoped resolver selection, DoQ downgrade, and the `NO_TCP_FALLBACK` adaptive-hint guard.
- **P4.2 (2026-04-27):** Per-app-family invalidation on package-version change landed via `DnsPathPreferenceInvalidator` (11 tests). Fastest-resolver cache per `(host, NetProfile)` with 30-min TTL landed (17 tests). Remaining: `DnsPathPreferenceInvalidator.register()` is not yet hooked into `Application.onCreate()` — separate wiring PR.

### 2026-04-23: Transparent TLS Family Engine

Status: COMPLETE in repo-owned scope.

- Finished the native first-flight family runtime so cached direct-path `tcp_family` now drives all six transparent TLS arms on round-one ClientHello sends: `SEG_PRE_SNI`, `SEG_MID_SNI`, `SEG_POST_SNI`, `REC_PRE_SNI`, `REC_MID_SNI`, and `TWO_PHASE_SEND`.
- Added weighted per-flow neighborhood rotation for the semantic split/record families instead of pinning one exact boundary forever, while keeping the learned cached family label stable.
- Implemented `two_phase_send` as a randomized split plus jittered inter-phase delay on the existing TCP write path, with per-flow `first_write_len` and `phase_gap_ms` selection.
- Added a typed transparent-family validation gate so invalid short-boundary plans or byte-mutating synthesized first flights are refused before the arm is applied; debug builds automatically enforce the ClientHello byte-preservation invariant.
- Kept winner caching on the existing direct-path capability store path, which already persists the learned family per network fingerprint + authority + IP-set tuple and now covers the full family set instead of only the earlier partial slice.
- Packet-capture validation on the Android VPN path remains a separate verification task, but the repo-owned engine/runtime, policy-model, and regression-test scope for the semantic TLS family epic is now landed.

### 2026-04-23: Direct-Mode Diagnostic Orchestrator Slice

Status: PARTIAL. Persistence, confirmation, TTL, and remediation ladder landed; ranked-arm dispatcher API landed (P4.3.1); attempt-budget, integration coverage, and relay-preset unification still deferred (ADR-010).

- Fixed the diagnostics persistence boundary so orchestrator outputs now survive the stored engine-wire path instead of losing `strategyRecommendation` after finalization.
- Threaded the typed direct-mode result through session projections and derived summaries, including an explicit positive `TRANSPARENT_WORKS` summary alongside the existing owned-stack and no-direct outcomes.
- Restored the Home audit fallback path that applies persisted strategy recommendations when a reusable full strategy-probe winner is unavailable.
- Added a direct-path policy persistence coordinator that consults the previously stored authority record before pinning a new verdict, so diagnostics now has a lightweight Phase 0 passive prior instead of always deciding from zero.
- Enforced confirmation-before-pin for persisted direct-mode policy: transparent and owned-stack outcomes now require corroborating DNS/active evidence or a matching previously confirmed record, and negative verdicts now require repeated active failures before they are promoted into stored policy.
- Added a 7-day direct-path policy TTL plus three-failure revalidation retirement; runtime policy resolution now excludes unconfirmed, over-failed, expired, or cooldown-expired `NO_DIRECT_SOLUTION` entries from the injected direct-path capability set.
- Added a transport-specific remediation branch in Diagnostics and Home: typed direct-mode verdict metadata now survives into both surfaces, and the ladder can hand users to the owned-stack browser, a browser-camouflage relay path, a QUIC-heavy relay path, or an explicit "no reliable relay hint yet" review branch instead of collapsing everything into generic History/Diagnostics fallback copy.
- Added a shared remediation selector plus focused unit/UI coverage so Home can also use saved authority capability evidence to choose between browser-camouflage and QUIC-heavy relay guidance before opening Mode Editor.
- Added focused diagnostics/service regressions for confirmation, failure-budget retirement, and runtime filtering.
- **P4.3.1 (2026-04-27):** Ranked-arm dispatcher API exposed in `direct_path_learning.rs` — 8 tests (ADR-010). Remaining deferred: per-class attempt-budget enforcement, deterministic integration coverage for the full class-to-arm execution ladder, and unifying Config relay preset suggestions onto the same transport-remediation selector.

### 2026-04-23: Owned-Stack Android 17 ECH

Status: COMPLETE in repo-owned scope.

- Promoted the earlier browser-only slice into a shared owned-stack request service: both RIPDPI Browser and the new repo-local `SecureHttpClient` surface now execute through one typed entry point instead of separate browser-local logic.
- Tightened Android 17 ECH policy from coarse device gating to authority-aware routing: the platform `HttpEngine` path is treated as confirmed-ECH only for hosts with fresh cached `ECH_CAPABLE` DNS evidence or explicit `xml-v37` enabled-domain overrides.
- Added automatic QUIC-capable platform request retry with QUIC disabled (H2-only) before native fallback, and surfaced the owned-stack execution trace so browser/UI consumers can distinguish confirmed ECH use, opportunistic platform use, H2 retry, and native fallback causes.
- Kept the static Android 17 `network_security_config` enforcement list explicit and synced it with the owned-stack Kotlin policy, while preserving graceful pre-17 fallback to the native owned-TLS bridge.
- Added focused service/browser regression coverage plus README/platform documentation for the owned-stack request API, Android 17 requirement, DNS capability dependency, and fallback matrix.
- Device-side proof still depends on running the committed code on an actual Android 17 environment with an ECH-capable authority, but there is no remaining repo-owned implementation gap in the browser / SDK / policy / fallback stack for this epic.

### 2026-04-23: Offline Learner Strategy-Pack Generation Slice

Status: PARTIAL. Pipeline generation and Thompson sampling scorer landed (P4.4.1); rarity/retry penalties, attempt-budget enforcement, shared-priors upload constraints, and sim-to-field calibration deferred (ADR-011).

- Extended the existing offline analytics pipeline so `publish` and `run-all` now emit `strategy-pack-catalog.candidate.json` instead of stopping at winner mappings and analyst reports.
- Added deterministic generated `offline-*` packs derived from stable device-fingerprint winner mappings, preserving the live strategy-pack schema and baseline metadata while keeping rollout staged at `0%`.
- Added strategy-pack-specific pipeline documentation, a schema file for the generated catalog shape, and regression coverage for pack emission on the checked-in sample corpus.
- Kept the output review-gated and offline-only: generated packs are not auto-consumed by the runtime and still require analyst review plus the normal signing/promotion flow before shipping.
- **P4.4.1 (2026-04-27):** `ThompsonSampling<K>` scorer added in `strategy_evolver/thompson_sampling.rs` — 12 tests. Remaining deferred (ADR-011): rarity/retry penalties, attempt-budget enforcement, shared-priors upload constraints, and emulator/sim-to-field calibration. Note: `lcg_f64` shifts by 33 yielding `[0, 0.5)` — works with current `epsilon = 0.1` but is biased; tracked in ADR-011.

### 2026-04-25: Strategy Evolver Time-Knob CLI Wiring

Status: COMPLETE.

- Closes the deferred follow-up from the time-aware selection change earlier today: the four numeric knobs on `StrategyEvolver` (experiment TTL, decay half-life, cooldown threshold, cooldown duration) are now configurable from `RuntimeConfig` rather than only the source defaults.
- Added `evolution_experiment_ttl_ms`, `evolution_decay_half_life_ms`, `evolution_cooldown_after_failures`, `evolution_cooldown_ms` to `RuntimeAdaptiveSettings` with sensible defaults (30 s / 1 h / 3 / 5 min) so existing configs are unaffected.
- Added matching CLI flags `--evolution-experiment-ttl-ms`, `--evolution-decay-half-life-ms`, `--evolution-cooldown-after-failures`, `--evolution-cooldown-ms` to `ripdpi-config`'s parser, including parse-error rejection on non-numeric input.
- Threaded the four values through `runtime/listeners.rs` via the new `StrategyEvolver::with_time_knobs` builder, leaving `StrategyEvolver::new` unchanged.
- Three new tests pass (`cli_parses_evolution_time_knobs`, `cli_rejects_invalid_evolution_time_knob_values`, `with_time_knobs_overrides_defaults`); the existing `cli_parses_strategy_evolution` was extended to assert the four defaults survive a partial-flag run.
- No diagnostics wire-contract bump needed -- these knobs live on the Rust-side `RuntimeConfig` and never cross the JNI host-pack ingestion contract.

### 2026-04-25: Strategy Evolver Time-Aware Selection (TTL, Decay, Cooldown)

Status: COMPLETE. In-evolver implementation and CLI wiring complete; host-pack proto wiring closed in cleanup epic (P4.1).

- Implemented the partial-adopt outcome of the timer/TTL/decay spike. The UCB1 strategy evolver in `ripdpi-runtime` now consumes wall-clock time on three read-side checks without adding a background timer thread: active-experiment TTL (default 30 s), idle-decay on combo stats (`exp(-Δt / half_life)` with default 1 h half-life), and consecutive-failure cooldown (default 3 → 5 min).
- Switched the evolver's internal timing to a monotonic `Instant`-based clock so TTL/decay/cooldown survive `SystemTime` jumps and NTP corrections. `ComboStats.last_attempt_ms` keeps its name and now carries the monotonic delta.
- Added a typed `CooldownTransition` returned from `record_attempt` so the evolver emits `tracing::debug!` events on cooldown trip and clear without leaking the bookkeeping into call sites.
- `select_next_combo` now skips cooled combos in the niche-winner cache and `best_context_combo_for_family`; when every bucket-matching pool entry is cooling, the new `pick_non_cooled_random_for_bucket` falls back to `pilot_combo_for_bucket` so the evolver always returns a hint.
- Eviction (`evict_if_needed`, `evict_context_if_needed`) is now decay-aware -- a stale winner with high raw success-rate no longer outranks a fresh combo with no signal yet.
- 8 new unit tests added (54 total in the `strategy_evolver` module): TTL drop without stats update, TTL=0 disables drop, decay-demotes-stale ranking, cooldown trip + clear, cooldown=0 disables gate, cooled-combo filtering, all-cooled fallback to pilot, monotonic-clock test-override semantics.
- **P4.1 (2026-04-27):** The four time-knobs (`experiment_ttl_ms`, `decay_half_life_ms`, `cooldown_after_failures`, `cooldown_ms`) are now wired through proto → `RipDpiAdaptiveFallbackConfig` → Settings UI, closing the deferred host-pack schema bump.

### 2026-04-27: Cleanup Epic — Phase 1–5

Status: COMPLETE (audit pass; deferred items tracked under "Open follow-ups" below).

A systematic incomplete-features audit was run against the Kotlin app and the
Rust workspace. ~41 atomic commits closed the actionable findings: roughly
half landed correctness or feature work, the rest re-classified findings as
already-resolved by-design and captured the rationale in 9 new ADRs
(`adr-005`..`adr-013`) plus an architecture index at
`docs/architecture/README.md`.

**Phase 1 — Quick Wins (9 commits):** explicit `publish = false` on
`ripdpi-bench`, `ripdpi-cli`, and the three test-support crates;
`CapabilityUnavailable::NotImplemented` removed from the public API
(never constructed); `community_api_url` surfaced in Settings;
community-stats loading/error states; helper text for full-tunnel-mode and
SeqOverlap-unsupported reason; owned-stack browser scope captured in
`docs/architecture/browser-route-scope.md` as remediation-only entry.

**Phase 2 — Correctness (8 commits):** Release/Acquire ordering on
`last_fd` publish; fd-ownership tightening (`IoUringTunContext` →
`OwnedFd`, `Submission` documented as non-owning);
`CancellationException` rethrow in `CommunityComparisonClient`,
`GeoIpChecker`, and `Tun2SocksTunnel` — the last also fixed
`nativeBindings.destroy()` silently skipping under cancel via
`withContext(NonCancellable)`; DNS resolver IPs stripped from VPN service
logs; `NoResolverIpInLogs` detekt rule added (7 unit tests);
`DetectionHistoryStore` injected via `@ApplicationContext` — closed an
Activity-context leak path through `DetectionCheckScheduler.runQuickCheck`.

**Phase 3 — Architectural Decisions (5 commits, all audit false positives):**
ADRs 005–009 record decisions on findings that were demonstrably
non-issues — Cloudflare `publish_local` already dispatched in
`UpstreamRelaySupervisor`; `RelaySession::open_datagram` serves
Hysteria2/TUIC/MASQUE UDP; Finalmask UI fields exist in `RelayFields.kt`;
Edge/Safari TLS profiles are minimal-by-design (Safari uses unique
`safari_fixed` extension order with no GREASE, Edge inherits Chromium
fingerprint shape); tier-3 platform primitives wired through root-helper IPC.

**Phase 4 — Feature Completion (8 commits):** see the dated sections above
for inline P4.X status — `P4.1` (Strategy Evolver host-pack proto wiring),
`P4.2` (DNS Enforcement), `P4.3` (Direct-Mode Diagnostic Orchestrator),
`P4.4` (Offline Learner Strategy-Pack Generation), and `P4.5` (developer
analytics stage timings) all have status notes pointing at the relevant
crate, test counts, and ADR references.

**Phase 5 — Strategic / Performance (6 commits):**
- Community comparison cache: 1 h TTL was already in place; added a clear
  button in Settings.
- ECH config rotation: introduced `EchConfigSource` trait,
  `BundledEchConfigSource` (returns the existing constant),
  `RemoteEchConfigSource` stub, and `CdnEchUpdater` with TTL+fallback
  semantics (5 unit tests). Remote DoH wiring deferred (ADR-012).
- io_uring: `stream_copy_uring` busy-wait wakeup and `tun.rs`
  registered-buffer TX path both deferred pending Criterion benchmark
  baselines (ADR-013). Investigation also surfaced a latent correctness
  issue in `batch_tun_write` (uses `send_zc` with `buf_index: 0` without
  a registered buffer — wrong opcode for plain writes; tracked in ADR-013).
- `SharingStarted.Eagerly`: 3 of 3 occurrences are intentional warm-ups
  in `ApplicationScope` / `ApplicationIoScope`, now justified by inline
  comments.
- Apps Script relay: actively dispatched via `UpstreamRelaySupervisor`;
  configuration path documented in KDoc (not orphan).

**Open follow-ups (as of 2026-04-27).** Each item has an owning ADR or
section that holds the implementation plan; nothing below is blocking the
current release.

| Area | Item | Tracked in |
|---|---|---|
| Direct-Mode | Per-class attempt-budget enforcement | ADR-010 |
| Direct-Mode | Deterministic integration coverage for the class-to-arm execution ladder | ADR-010 |
| Direct-Mode | Unify Config relay preset suggestions onto the transport-remediation selector | ADR-010 |
| Offline Learner | Bayesian rarity / retry penalties | ADR-011 |
| Offline Learner | Attempt-budget enforcement in the learner | ADR-011 |
| Offline Learner | Shared-priors upload constraints (max payload, rate limit) | ADR-011 |
| Offline Learner | Emulator / sim-to-field calibration beyond archive mining | ADR-011 |
| Offline Learner | Fix `lcg_f64 >> 33` bias (currently yields `[0, 0.5)`) | ADR-011 |
| Monitor | `RemoteEchConfigSource` via DoH HTTPS RR query + scheduler integration | ADR-012 |
| io_uring | Switch `stream_copy_uring` busy-wait to `thread::park` / `unpark` | ADR-013 |
| io_uring | Registered-buffer TX path in `tun.rs::batch_tun_write` | ADR-013 |
| io_uring | Fix `send_zc(buf_index: 0)` opcode bug in `batch_tun_write` | ADR-013 |
| DNS | Hook `DnsPathPreferenceInvalidator.register()` into `Application.onCreate()` | DNS Enforcement Slice (above) |

See `docs/architecture/README.md` for the ADR index.

## Roadmap Hygiene

- Keep `ROADMAP.md` updated in the same change as every future roadmap-scoped implementation.
- Use this file for incremental hardening and productization follow-ups instead of resurrecting removed historical roadmaps.

## Removed Completed Roadmaps

The following completed roadmap files were removed because they had become
stale implementation archives rather than active planning documents:

- `ROADMAP-architecture-refactor.md`
- `ROADMAP-bypass-techniques.md`
- `ROADMAP-bypass-modernization.md`
- `docs/roadmap-execution-queue.md`
- `docs/roadmap-integrations.md`
