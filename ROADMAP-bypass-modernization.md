# ROADMAP: DPI Bypass Modernization

> Next-stage roadmap derived from the 2026 external technical review.
> This roadmap is intentionally separate from the completed audit roadmap and
> the earlier technique-expansion checklist. It focuses on architecture,
> measurement, and product hardening needed to keep RIPDPI effective on modern
> Android networks.

## Execution Status (2026-04-18)

- **Workstream 1 (Capability Hygiene):** COMPLETE. `RuntimeCapability` /
  `CapabilityUnavailable` / `CapabilityOutcome<T>` types in
  `ripdpi-runtime::platform`; TTL write failures surface explicitly via
  `try_set_stream_ttl_with_outcome` in `platform/linux.rs`; ~22 candidates
  in `ripdpi-monitor/src/candidates.rs` carry `requires_capabilities` tags;
  `FailureClass::CapabilitySkipped` variant landed cross-language (Rust
  + Kotlin `DiagnosticsOutcomeBucket.Inconclusive`); `record_capability_skipped`
  telemetry recorder available; strategy evolver `record_failure` short-circuits
  on `CapabilitySkipped` so 3-skipped + 1-success ranks identical to 1-success;
  candidate enumeration split into primary / opportunistic / rooted pools with
  graceful-fallback allowlist preserving HTTP / TLS / TLS-ECH / QUIC v1 /
  QUIC v2 probe matrix coverage on non-rooted Android.
- **Workstream 2 (First-Flight IR):** COMPLETE. The design doc lives in
  `docs/architecture/first-flight-ir.md`; `ripdpi-desync` exports a shared
  first-flight IR for TLS ClientHello and QUIC Initial; `ripdpi-packets`
  exposes `TlsClientHelloLayout` / `QuicInitialLayout`; the TLS prelude and
  QUIC UDP planner consume the IR for record-boundary, authority, and
  CRYPTO-frame decisions; fake-family classification after TLS prelude is now
  semantic rather than raw-parser-gated; QUIC prelude builders now seed their
  packets from IR-derived Initial metadata; terminal fake-step lowering routes
  through runtime lowering instead of planner degradation; and dedicated rewrite
  goldens live under `ripdpi-desync/tests/golden/phase4_*.json`.
- **Workstream 3 (QUIC Initial Shaping Subsystem):** COMPLETE.
  `docs/architecture/quic-initial-packetizer.md` now defines the packetizer
  boundary; `ripdpi-packets` exposes `QuicInitialSeed`,
  `QuicInitialPacketLayout`, and browser-profiled packet builders/parser
  helpers; `plan_udp.rs` routes `DummyPrepend`, `QuicSniSplit`,
  `QuicCryptoSplit`, `QuicMultiInitialRealistic`, padding ladder, fake-version,
  and version-negotiation decoy packets through the packetizer; weak QUIC
  mutation families were removed from the production probe pool; and
  `ripdpi-monitor` plus Kotlin diagnostics exports now record the exact QUIC
  layout family that won.
- **Workstream 4 (DNS Oracle And Resolver-Policy Hardening):** COMPLETE.
- **Workstream 5:** Partial. Browser-family template metadata, explicit
  desktop/ECH profile variants, ALPN-aware monitor binding, and Android
  owned-TLS metadata export are now landed. Packet-level choreography,
  Android ECH policy/fallback integration, fake-family replacement, and
  acceptance corpus work remain open. See `docs/roadmap-execution-queue.md`.
- **Workstream 6:** COMPLETE. The learning redesign is now landed:
  contextual strategy-evolver state, niche-winner retention, combo-aware
  reward scoring, adaptive-wrapper learning context with hosting/reachability
  buckets, bucket-aware stratified pilot evaluation, and
  methodology-versioned strategy-probe exports.
- **Workstream 7:** COMPLETE. ADR-004 now defines the three emitter tiers,
  step kinds are classified in `ripdpi-config`, strategy candidates carry
  explicit emitter-tier metadata, fake-flag and broad-fragmentation variants
  are confined to the full-matrix lab pool, diagnostics summaries label
  rooted-only or downgraded winners, and the app-facing diagnostics UI now
  surfaces emitter / realization labels plus fallback notes.
- **Workstream 8:** Complete. `VpnProtectSocketServer` emits explicit
  protect-failure runtime events, the Unix-socket protect fallback rejects the
  native caller when `VpnService.protect()` fails, `RipDpiVpnService` now uses
  an explicit upstream-binding audit boundary with no `allowBypass()` /
  `bindSocket()` path, VPN and proxy runtimes expose explicit handover state in
  service telemetry, resolver/failover state resets are explicit on handover
  restart, and settings surface proxy-mode TLS/ECH limitations. See
  `docs/roadmap-execution-queue.md`.
- **Workstream 9:** Complete. The static server-acceptance corpus fixture,
  repeated self-hosted lab matrix, diagnostics archive schema v4 measurement
  exports, rollout-gate assessment, and packet-capture replay summaries are all
  committed under the `contract-fixtures/` and `scripts/ci/` Phase 16 surface.
  Workstream 9 remains continuous in operation, but the roadmap implementation
  slices are landed. See `docs/roadmap-execution-queue.md`.

## Related Roadmaps

This roadmap is the **strategic** counterpart to the tactical technique list. It
defines the planner, emitter, and measurement architecture the rest of the bypass
work must fit into. See the three sibling roadmaps below for connected work:

- [ROADMAP.md](ROADMAP.md) -- master index and cross-roadmap sequencing.
- [ROADMAP-bypass-techniques.md](ROADMAP-bypass-techniques.md) -- tactical checklist
  (Tier 1 + 2 shipped, Tier 3 deferred). New tactics route through this roadmap's
  IR and capability model; Workstream 1 here gates trustworthy evaluation of items there.
- [ROADMAP-architecture-refactor.md](ROADMAP-architecture-refactor.md) -- structural
  cleanup that must land first for Workstreams 2 and 3 of this roadmap to have a
  clean base. Specifically:
  - Refactor Workstream 0 (guardrails) and Workstream 1 (config contract) are
    now complete; new planner or IR work here should consume those shipped
    seams instead of widening the old config contract surface.
  - Refactor Workstream 3 (runtime/desync decomposition) created the seams that
    this roadmap's Workstream 2 (first-flight IR) and Workstream 3 (QUIC subsystem)
    lower onto. That runtime split and the follow-on QUIC packetizer work are
    now complete, so the next blocker is Workstream 5's TLS shaping and
    browser-family template work rather than missing runtime
    lowering/capability/UDP/platform seams or residual planner migration.
  - Refactor Workstream 4 (service/relay orchestration) interacts with Workstream 8
    (Android hardening) -- VPN lifecycle, handover, and relay-kind resolvers are
    the same code paths under different lenses.
- [docs/roadmap-integrations.md](docs/roadmap-integrations.md) -- integration-layer
  roadmap. TLS fingerprint catalogs, strategy-pack rollout, and Finalmask modes
  feed Workstream 5 (TLS shaping, browser-family templates) of this roadmap. New
  browser-family TLS templates ship through strategy-pack catalogs, not hardcoded.

### Ownership Notes

This roadmap defines architecture; the sibling roadmaps define concrete catalog
entries, tactics, or code seams.

| Topic | This roadmap owns | Sibling roadmap owns |
|-------|-------------------|---------------------|
| Capability snapshot model | Workstream 1 | techniques consumes (new tactics must honor it) |
| First-flight IR | Workstream 2 | techniques migrates step kinds onto it |
| QUIC Initial subsystem | Workstream 3 | techniques contributes probe candidates, not new packet families |
| DNS oracle scoring | Workstream 4 | techniques ships concrete cache behavior (#10) |
| TLS template families | Workstream 5 | integrations delivers catalog payloads |
| Emitter tiers (non-root / rooted / lab) | Workstream 7 | techniques classifies existing step kinds |
| Runtime code layout | consumes refactor Workstream 3 output | refactor Workstream 3 owns the split |

## Design Direction

The review's main conclusions are the planning baseline for this roadmap:

- Keep parser-aware, spec-valid first-flight shaping as the primary bypass path.
- Treat root as an emitter capability upgrade, not a separate strategy system.
- Demote non-root TTL tricks, generic fake packets, and malformed tactics from
  the production critical path.
- Make QUIC Initial shaping a first-class subsystem rather than a bag of
  packet mutations.
- Stop treating encrypted DNS as literal ground truth. Use it as a
  confidence-scored signal with multi-oracle validation.
- Replace single-domain elimination and context-free learning with
  network-aware evaluation and adaptation.

## Status Legend

- [ ] Not started
- [~] In progress
- [x] Complete
- [lab] Keep for diagnostics or research, not for production defaults

## Priority Order

1. Capability hygiene and de-risking
2. Unified first-flight IR and planner refactor
3. QUIC Initial shaping subsystem
4. DNS oracle and resolver-policy hardening
5. TLS shaping, browser-family templates, and ECH integration
6. Strategy evaluation and learning redesign
7. Root/non-root emitter rationalization
8. Android networking hardening
9. Measurement, lab tooling, and rollout gates

## Workstream 1: Capability Hygiene And De-Risking

**Status:** [x] Complete (2026-04-16)
**Priority:** P0
**Why first:** The runtime needed honest emitter accounting before any further
planner or tactic work could be evaluated credibly.

**Shipped scope (2026-04-16):**

- `ripdpi-runtime::platform` now defines `RuntimeCapability`,
  `CapabilityUnavailable`, and `CapabilityOutcome<T>` as the shared capability
  surface.
- TTL write failures now surface explicitly through
  `try_set_stream_ttl_with_outcome` in `platform/linux.rs` instead of silently
  degrading into ordinary writes.
- Strategy candidates in `ripdpi-monitor/src/candidates.rs` are tagged with
  `requires_capabilities`, and candidate enumeration now splits into primary,
  opportunistic, and rooted pools.
- `FailureClass::CapabilitySkipped` is wired across Rust and Kotlin reporting,
  telemetry, and routing invariants, with the Kotlin side mapping it to
  `DiagnosticsOutcomeBucket.Inconclusive`.
- Strategy learning ignores failed-emitter runs via the
  `record_failure(CapabilitySkipped)` short-circuit.
- Regression coverage now pins TTL-fail, protect-fail, and raw-path-unavailable
  outcomes so they do not collapse into generic censorship failures.

**Primary areas:**
- `native/rust/crates/ripdpi-runtime/src/platform/linux.rs` (2492 lines)
- `native/rust/crates/ripdpi-runtime/src/platform/mod.rs` (1398 lines)
- `native/rust/crates/ripdpi-root-helper/src/protocol.rs` (IPC surface)
- `native/rust/crates/ripdpi-monitor/src/engine/runners/strategy.rs` (997 lines)
- `native/rust/crates/ripdpi-monitor/src/classification/strategy.rs` (269 lines)
- `core/diagnostics/` (Kotlin finalization + reporting)

**Completed tasks**

- [x] Introduced an explicit runtime capability snapshot for every connection
  path:
  - TTL write support
  - raw TCP fake-send support
  - raw UDP / fragmentation support
  - replacement-socket support
  - root-helper availability
  - VPN protect callback availability
  - network binding support
- [x] Propagated capability failures as structured results instead of silently
  falling back to unmodified sends.
- [x] Reclassified non-root TTL-dependent tactics as opportunistic only.
- [x] Marked capability-based skips distinctly in diagnostics, strategy probes,
  and learning telemetry.
- [x] Removed capability-blind winner promotion from quick scans and home
  analysis when the tactic was never actually emitted as designed.
- [x] Added regression tests proving TTL failure, protect failure, and raw-path
  unavailability do not collapse into a generic "network blocked" outcome.

**Production demotions shipped in this phase**

- [x] Moved non-root `Disorder` and generic `Fake` out of the primary candidate
  set unless capability checks pass first.
- [x] Moved fake-RST-heavy flows to rooted or lab-only candidates by default.
- [x] Demoted fixed duplicate `HostFake` behavior from default candidate sets,
  while preserving primary coverage through the graceful-fallback allowlist for
  the hostfake variants that still belong in production probing.

**Completed outcomes**

- Every candidate execution records whether the intended emitter path was used.
- TTL or raw-path failures appear as explicit capability outcomes.
- Strategy learning ignores failed-emitter runs when ranking tactics.

## Workstream 2: Unified First-Flight IR And Planner Refactor

**Status:** [x] Complete
**Priority:** P0
**Why now:** TLS prelude mutation, TCP step planning, and QUIC packet shaping
currently live in separate planners. That makes cross-layer strategies harder
to express and encourages feature-specific special cases. This workstream
lowers onto the seams opened by architecture-refactor Workstream 3 and should
use that finished runtime split as its base; the remaining dependency is the
completed Workstream 3 runtime seam, not more runtime decomposition.

**Current state (2026-04-17):**
- TLS ClientHello parsing: `ripdpi-packets/src/tls_nom.rs` (25 KB, nom parser
  `parse_client_hello_record` / `parse_client_hello_handshake`) and
  `ripdpi-packets/src/tls.rs` (55 KB, `tls_client_hello_marker_info_in_handshake`
  at line 48 emits `TlsMarkerInfo { ext_len_start, ech_ext_start, sni_ext_start }`).
- QUIC Initial parsing: `ripdpi-packets/src/quic.rs` (34 KB, `parse_quic_initial`,
  `QuicInitialInfo`, QUIC v1 and v2 salt constants at lines 27-34).
- TCP step planner: `ripdpi-desync/src/plan_tcp.rs` (380 lines, `plan_tcp()` entry).
- UDP step planner: `ripdpi-desync/src/plan_udp.rs` (296 lines, `plan_udp()` entry).
- Runtime prerequisite status: architecture-refactor Workstream 3 is complete.
  The landed IR foundation, TCP semantic offset migration, terminal fake-step
  lowering migration, and rewrite goldens now sit on the finished runtime
  split; the remaining Workstream 2 blocker is planner-wide migration inside
  `ripdpi-desync`, not more runtime decomposition.

**Shipped scope (2026-04-17):**

- `docs/architecture/first-flight-ir.md` defines the IR boundary, invariants,
  and the separation between semantic shaping and lowering.
- `ripdpi-packets` now exposes `TlsClientHelloLayout`, `TlsExtensionInfo`,
  `QuicCryptoFrameInfo`, and `QuicInitialLayout` so parser structure is
  available without duplicating byte-walking logic in planners.
- `ripdpi-desync::first_flight_ir` now normalizes TLS ClientHello and QUIC
  Initial packets into a shared IR carrying authority, ALPN, ECH/GREASE state,
  TLS record boundaries, QUIC CRYPTO frame boundaries, and desired TCP/UDP
  boundary hints.
- `tls_prelude.rs` now consumes the IR when constructing TLS record
  fragmentation state, including truncated fake-TLS inputs such as
  `DEFAULT_FAKE_TLS`.
- `plan_udp.rs` now consumes the QUIC IR for SNI split and CRYPTO split
  decisions instead of re-reading the old `QuicInitialInfo` parser surface.

**Primary areas:**
- `native/rust/crates/ripdpi-desync/src/plan_tcp.rs` (380 lines)
- `native/rust/crates/ripdpi-desync/src/plan_udp.rs` (296 lines)
- `native/rust/crates/ripdpi-config/` (model authority)
- `native/rust/crates/ripdpi-packets/src/tls.rs` / `tls_nom.rs` / `quic.rs`
- `native/rust/crates/ripdpi-runtime/src/runtime/desync.rs` (consumer -- 4654 lines,
  gated on architecture-refactor Workstream 3)

**Target architecture**

- Semantic first-flight IR:
  - SNI / host data
  - ALPN
  - supported versions / groups / key shares
  - GREASE state
  - ECH / ECH-GREASE state
  - TLS record boundaries
  - QUIC CRYPTO frame layout
  - desired datagram / segment boundaries
- Shaping pass:
  - extension permutation
  - padding and size targets
  - key-share / supported-group shaping
  - ALPN-aware templates
  - record / CRYPTO split decisions
- Emission pass:
  - TLS records
  - QUIC CRYPTO frames
  - TCP segment plan
  - UDP datagram plan
- Capability lowering pass:
  - non-root safe emission
  - rooted exact emission
  - lab-only raw paths

**Tasks**

- [x] Define a shared first-flight IR module boundary.
- [x] Add parsers that normalize TLS ClientHello and QUIC Initial into
  the same high-level representation.
- [x] Migrate existing `TlsRec`, `TlsRandRec`, and QUIC prelude logic onto the
  new IR.
- [x] Migrate the remaining split/fake planner logic onto the IR.
- [x] Move terminal-step and emitter-specific restrictions out of semantic
  planning and into lowering.
  Current landed scope: terminal `FakeSplit` / `FakeDisorder` no longer
  degrade in `plan_tcp.rs`; runtime lowering owns that fallback path and its
  fail-closed behavior for original TCP flag overrides; and planner-owned
  seqovl capability downgrade is removed so semantic planning no longer forks
  on emitter support.
- [x] Preserve existing JSON / DSL / strategy-pack compatibility while adding a
  richer internal representation.
- [x] Add golden fixtures and regressions for:
  - TLS record fragmentation
  - existing TLS golden ClientHello vectors
  - QUIC CRYPTO frame splits
  - ECH / GREASE-preserving rewrites
  Landed in `ripdpi-desync/tests/golden/phase4_*.json`, blessed from the
  dedicated Phase 4 golden tests so TLS/QUIC rewrite outputs stay committed and
  reproducible.
- [x] Document the new planner stages and invariants.

**Done when**

- TLS and QUIC first-flight tactics share one planning pipeline.
- Root vs non-root differences are handled in lowering, not in tactic design.
- Existing production tactics can be expressed without planner-specific hacks.

## Workstream 3: QUIC Initial Shaping Subsystem

**Status:** [x] Complete (2026-04-17)
**Priority:** P1
**Why now:** The current QUIC logic has useful primitives but does not yet own
the full wire image of the Initial flight. Modern QUIC-aware DPI hinges on how
Initial packets, CRYPTO frames, padding, and version behavior are laid out.

**Current tactic locations in `plan_udp.rs`:**
- `FakeBurst` -- line 41 (uses `adjusted_udp_burst_count()`)
- `DummyPrepend` -- line 48 (`build_dummy_prepend_packets()`)
- `QuicSniSplit` -- line 49 (`build_quic_sni_split_packets()`)
- `QuicCryptoSplit` -- line 55 (`build_quic_crypto_split_packets()`)
- `QuicCidChurn` -- line 61 (`build_quic_cid_churn_packets()`)
- `QuicPacketNumberGap` -- line 64 (`build_quic_packet_number_gap_packets()`)
- `QuicMultiInitialRealistic` -- line 70 (`build_quic_multi_initial_realistic_packets()`)

Packet construction (HKDF key derivation, packet number encryption,
`build_realistic_quic_initial`) lives in `ripdpi-packets/src/quic.rs`.

**Primary areas:**
- `native/rust/crates/ripdpi-desync/src/plan_udp.rs` (296 lines)
- `native/rust/crates/ripdpi-runtime/src/runtime/udp.rs` (964 lines)
- `native/rust/crates/ripdpi-packets/src/quic.rs` (34 KB)
- `native/rust/crates/ripdpi-monitor/src/candidates.rs` (probe candidate lists)

**Landed scope**

- `docs/architecture/quic-initial-packetizer.md` now defines the packetizer
  invariants, production layout families, and demoted lab-only families.
- `ripdpi-packets/src/quic.rs` now owns a reusable QUIC Initial packetizer via
  `QuicInitialSeed`, `QuicInitialPacketLayout`, `parse_quic_initial_seed(...)`,
  `packetize_quic_initial(...)`, and browser-like QUIC profile builders for
  Chrome/Firefox Android.
- `plan_udp.rs` now emits `DummyPrepend`, `QuicSniSplit`,
  `QuicCryptoSplit`, `QuicMultiInitialRealistic`, padding ladder,
  fake-version, and version-negotiation decoy packets through the packetizer
  instead of handwritten payload mutations.
- Production QUIC probe defaults in `ripdpi-monitor/src/candidates.rs` now
  prefer packetizer-backed layout families and demote `FakeBurst`,
  `QuicCidChurn`, and `QuicPacketNumberGap` out of the production pool.
- Strategy-probe exports in Rust and Kotlin now record the exact QUIC layout
  family that won so archive/rendered diagnostics preserve the Initial layout
  that actually succeeded.

**Tasks**

- [x] Implement a real QUIC Initial packetizer with control over:
  - CRYPTO offsets
  - frame ordering
  - PADDING placement
  - datagram count
  - coalescing
  - 1200-byte expansion rules
  - v1 / v2 selection
  - version-negotiation decoys
- [x] Rework `DummyPrepend`, `QuicCryptoSplit`, `QuicSniSplit`, and
  `QuicMultiInitialRealistic` to emit through the packetizer rather than by
  hand-built packet families.
- [x] Add realistic browser-like QUIC profiles instead of generic fake bursts.
- [x] Demote or remove low-evidence QUIC tactics from production defaults:
  - `FakeBurst`
  - `QuicCidChurn`
  - `QuicPacketNumberGap`
- [x] Add server capability and acceptance probes for:
  - QUIC version support
  - QUIC v2 support
  - compatible version negotiation behavior
  - handshake acceptance under split/coalesced Initial layouts
- [x] Extend diagnostics exports so QUIC winners record the exact Initial
  layout family that succeeded.

**Done when**

- QUIC tactics are described as Initial-layout strategies rather than ad hoc
  packet mutations.
- Diagnostics can tell which QUIC layout dimensions mattered.
- Weak QUIC mutations are no longer part of the production critical path.

## Workstream 4: DNS Oracle And Resolver-Policy Hardening

**Status:** [x] Complete
**Priority:** P1
**Why now:** The current system treats encrypted DNS too much like a ground
truth oracle. That risks false positives on CDN-heavy targets and makes oracle
failures feed bad data back into strategy selection.

**Current state:** `ripdpi-monitor` now ships the richer DNS outcome taxonomy
(`dns_compatible_divergence`, `dns_suspicious_divergence`,
`dns_sinkhole_substitution`, `dns_nxdomain_mismatch`,
`dns_oracle_unavailable`) plus a dedicated `dns_oracle.rs` assessment layer
that records oracle trust, confidence score, agreement/disagreement sets, and
per-resolver attempts for diagnostics. Probe and strategy-baseline paths no
longer treat fallback-only encrypted DNS success as a trusted oracle, which
reduces false positives on CDN-heavy or partially degraded networks.

`ripdpi-dns-resolver` now folds oracle trust into resolver health, quarantines
repeatedly divergent or poisoned endpoints, and scopes resolver ranking plus
fallback memory by an opaque network identity token so callers can separate
Wi-Fi/cellular, IPv4/IPv6, operator, or transport memory without teaching the
crate Android-specific network semantics.

**Primary areas:**
- `native/rust/crates/ripdpi-dns-resolver/src/resolver.rs` (1406 lines)
- `native/rust/crates/ripdpi-dns-resolver/src/pool.rs` (392 lines)
- `native/rust/crates/ripdpi-dns-resolver/src/health.rs` (296 lines)
- `native/rust/crates/ripdpi-monitor/src/strategy.rs`
- `native/rust/crates/ripdpi-monitor/src/classification/strategy.rs` (269 lines)
- `native/rust/crates/ripdpi-monitor/src/dns_analysis.rs`
- `native/rust/crates/ripdpi-runtime/src/dns_hostname_cache.rs` (shipped via
  bypass-techniques #10; this workstream layers scoring on top)
- tunnel DNS intercept and diagnostics surfaces

**Tasks**

- [x] Replace single-oracle logic in `ripdpi-monitor` with multi-oracle trust
  scoring and confidence metadata.
- [x] Extend that oracle scoring into resolver selection and ranking policy in
  `ripdpi-dns-resolver`.
- [x] Key resolver health and fallback memory by network identity instead of a
  global process-wide ordering.
- [x] Track ranking separately for:
  - Wi-Fi vs cellular
  - IPv4 vs IPv6
  - resolver operator
  - transport protocol
- [x] Add RRset-aware comparison classes:
  - exact match
  - compatible divergence
  - suspicious divergence
  - likely sinkhole
  - NXDOMAIN mismatch
  - oracle unavailable
- [x] Score oracle health before using an encrypted result as a diagnostic
  reference in probe and strategy-baseline classification.
- [x] Quarantine untrusted oracle outcomes so they do not retrain the strategy
  system from strategy-baseline DNS evidence.
- [x] Use hedged queries only for bootstrap, failover, and diagnostics rather
  than as the default resolution path.
- [x] Add tests for CDN variance, IPv4/IPv6 asymmetry, partial overlap, and
  multi-resolver disagreement.

**Follow-on ideas (non-gating)**

- Evaluate DDR / HTTPS / SVCB-driven resolver discovery on top of the existing
  HTTPS-record parsing surface where it materially improves bootstrap coverage.

**Done when**

- `dns_substitution` is no longer emitted from a simple no-overlap rule.
- Resolver ranking is network-scoped.
- Oracle failures are visible and do not pollute strategy recommendations.

## Workstream 5: TLS Shaping, Browser-Family Templates, And ECH

**Status:** [x] Complete (2026-04-18)
**Priority:** P1
**Why now:** The strongest long-term TLS evasions are still the spec-valid,
parser-aware ones, but they need to look coherent. Randomized fake bytes are
not enough against modern fingerprinting.

**Current state:** `ripdpi-tls-profiles` is 535 lines with per-browser modules
already scaffolded: `chrome.rs`, `firefox.rs`, `safari.rs`, `edge.rs`, plus
`apply.rs`, `profile.rs` (97 lines), and `lib.rs` (246 lines).
`select_rotated_profile()` uses deterministic weighted rotation
(Chrome 65% / Firefox 20% / Safari 10% / Edge 5% at lib.rs line 49). ECH
fallback configs are hardcoded for Cloudflare in
`ripdpi-monitor/src/cdn_ech.rs` (250 lines, line 32 `ech_config_list`) with
a `TODO` at line 8 to add a periodic refresh mechanism. `TlsMarkerInfo` in
`ripdpi-packets/src/tls.rs` already populates `ech_ext_start`. Template
catalog distribution is the integrations roadmap's strategy-pack pipeline
(see `strategy-pack-operations.md`).

**Primary areas:**
- `native/rust/crates/ripdpi-tls-profiles/` (535 lines, 6 files)
- `native/rust/crates/ripdpi-desync/` (prelude shaping)
- `native/rust/crates/ripdpi-packets/src/tls.rs`
- `native/rust/crates/ripdpi-monitor/src/cdn_ech.rs` (250 lines, ECH refresh TODO)
- resolver bootstrap and HTTPS/SVCB handling in `ripdpi-dns-resolver`
- Android TLS / ECH integration points

**Tasks**

- [x] Introduce the first browser-family TLS template set:
  - Chrome Android-like (`chrome_stable`)
  - Chrome desktop-like (`chrome_desktop_stable`)
  - Firefox-like (`firefox_stable`)
  - ECH-capable variant (`firefox_ech_stable`)
- [x] Add first-class template metadata controls for:
  - extension order / permutation family
  - GREASE placement style
  - supported-groups shaping
  - key-share shaping
  - ALPN-aware handshake template
- [~] Bind the shared template metadata into real code paths:
  - monitor TLS / ECH probes now derive ALPN from the selected template plan
  - planned TLS1.2 / TLS1.3 / ECH template ids and tracks are emitted in
    HTTPS probe details
  - the Android owned-TLS bridge now exports browser/template metadata to
    Kotlin
- [ ] Finish packet-level record-size choreography.
- [ ] Add controlled HelloRetryRequest-oriented tactics where server behavior
  makes them useful.
- [~] Integrate ECH / ECH-GREASE planning with DNS bootstrap instead of treating
  ECH as a separate afterthought.
  Current slice: ECH probe planning now points at `firefox_ech_stable`, while
  DNS HTTPS/SVCB bootstrap still feeds the actual ECH config acquisition path.
- [ ] Add Android-specific ECH integration research and implementation tasks:
  - API availability checks
  - configuration bootstrap
  - policy gating
  - fallback behavior
- [ ] Detect and surface proxy-mode interactions that suppress browser-native
  ECH so the product can compensate or warn appropriately.
- [ ] Replace generic fake packet families with coherent client-profile
  families; keep purely random fake data only for lab use.
- [ ] Build a server-acceptance corpus across major CDNs and stacks for every
  new TLS template family.

**Done when**

- TLS tactics can be described as coherent client templates plus shaping
  choices.
- ECH is planned together with DNS bootstrap and ALPN.
- Random fake-client generation is no longer a production default.

## Workstream 6: Strategy Evaluation And Learning Redesign

**Status:** [x] Complete (2026-04-18)
**Priority:** P1
**Why now:** One-domain elimination and context-light learning are too coarse
for a system whose success varies by network, route, host class, and transport.

**Current state:** The redesign is landed. The strategy probe runner now uses
bucket-aware stratified pilot targets instead of a one-domain qualifier, and
`strategy_evolver.rs` now keeps contextual bucket state, niche winners per
bucket, and family-first selection before parameter selection. Runtime adaptive
wrappers feed network identity, route capability, ALPN, ECH, resolver-health
class, root/non-root state, hosting family, and domestic/foreign reachability
into that evolver. The reward model now accounts for stability, latency
variance, detectability, and energy cost instead of only binary success plus
latency. Exported `StrategyProbeReport` payloads now carry
`methodologyVersion=strategy_learning_v3` plus pilot-bucket metadata so newer
learning output is distinguishable from older reports, and emitter/runtime
execution failures are excluded from tactic scoring so learning only reflects
real on-wire outcomes.

**Primary areas:**
- `native/rust/crates/ripdpi-monitor/src/engine/runners/strategy.rs` (997 lines)
- `native/rust/crates/ripdpi-runtime/src/strategy_evolver.rs` (1170 lines,
  UCB1 + epsilon-greedy)
- `native/rust/crates/ripdpi-monitor/src/classification/strategy.rs` (269 lines)
- runtime policy and learned-host machinery
- diagnostics summaries and exports

**Tasks**

- [x] Replace the one-domain qualifier round with stratified pilot evaluation.
- [x] Define target buckets for evaluation:
  - CDN / hosting family
  - TLS vs QUIC
  - ALPN class
  - ECH-capable vs non-ECH
  - domestic vs foreign reachability set
- [x] Keep niche-winner pools instead of permanently pruning tactics that lose
  early on one class of target.
- [x] Expand reward signals beyond binary success:
  - success rate
  - added RTT / latency tax
  - acceptance stability
  - failure variance
  - energy / cost budget
  - detectability / anomaly budget
- [x] Replace the current flat session-level exploration model with a
  hierarchical contextual bandit:
  - choose strategy family first
  - choose family parameters second
  - reset or forget on network change
- [x] Feed network identity, transport capability, ALPN, ECH availability,
  resolver health, and root/non-root state into the learning context.
- [x] Ensure failed emitter paths are excluded from tactic scoring.
- [x] Version exported diagnostics so evaluation methodology changes do not
  confuse old and new reports.

**Done when**

- Winners are chosen from stratified evidence, not one target.
- Learning is network-aware and capability-aware.
- Reports explain why a tactic won, not just that it won.

## Workstream 7: Root/Non-Root Emitter Rationalization

**Status:** [x] Complete (2026-04-18)
**Priority:** P2
**Why now:** Root should widen the set of exact emitters, not create a separate
strategy universe. The production rooted set should also be much smaller than
the research set.

**Current root-helper IPC surface** (`ripdpi-root-helper/src/protocol.rs`):
- `CMD_PROBE_CAPABILITIES` (line 43)
- `CMD_SEND_FAKE_RST`
- `CMD_SEND_FLAGGED_TCP_PAYLOAD`
- `CMD_SEND_SEQOVL_TCP`
- `CMD_SEND_MULTI_DISORDER_TCP`
- `CMD_SEND_ORDERED_TCP_SEGMENTS`
- `CMD_SEND_IP_FRAGMENTED_TCP`
- `CMD_SEND_IP_FRAGMENTED_UDP`

Seven active handlers in `ripdpi-root-helper/src/handlers.rs`. The first
emitter-tier slice is now landed:

- `docs/architecture/adr-004-emitter-tiers.md` defines three tiers:
  non-root production, rooted production, and lab / diagnostics only
- `ripdpi-config/src/model/mod.rs` now classifies TCP and UDP step kinds into
  those tiers
- `ripdpi-monitor/src/candidates.rs` now derives candidate tiers from that
  classification, keeps rooted production in the normal pool, and confines
  fake-flag / broad-fragmentation / full-matrix experiments to lab-only pools
- strategy-probe reports and Kotlin diagnostics models now carry emitter-tier
  and downgrade metadata so diagnostics exports can explain rooted-only or
  downgraded winners

**Primary areas:**
- `native/rust/crates/ripdpi-runtime/src/platform/` (linux.rs 2376 lines, mod.rs 1176)
- `native/rust/crates/ripdpi-root-helper/src/protocol.rs` (IPC surface)
- `native/rust/crates/ripdpi-root-helper/src/handlers.rs` (7 handlers today)
- `native/rust/crates/ripdpi-config/src/model/mod.rs` (`supports_*` validators)
- `native/rust/crates/ripdpi-monitor/src/candidates.rs` (candidate generation)
- advanced settings / diagnostics labeling in Kotlin

**Tasks**

- [x] Define three emitter tiers:
  - non-root production
  - rooted production
  - lab / diagnostics only
- [x] Audit every existing tactic into one of those tiers.
- [x] Keep rooted production focused on current-evidence techniques:
  - exact fragmentation
  - exact packet ordering
  - exact flag control where proven useful
- [x] Move exotic or weak-evidence tactics to lab-only:
  - fake-RST-heavy experiments
  - generic weird-flag combinations without recent wins
  - broad fragmentation variants without active measurements
- [x] Make the planner choose one tactic description and let the emitter tier
  determine whether the exact realization is available.
- [x] Add explicit UI / diagnostics labeling when a tactic required root or was
  downgraded from exact to approximate emission.
  Landed scope: structured diagnostics/report labeling plus app-facing
  diagnostics UI metrics and notes for emitter tier, rooted exact paths, and
  approximate fallback execution.

**Done when**

- Root changes emission precision, not the high-level strategy taxonomy.
- Production rooted tactics have current evidence behind them.
- Lab-only tactics remain available for research without polluting defaults.

## Workstream 8: Android Networking Hardening

**Status:** [x] Complete (2026-04-18)
**Priority:** P2
**Why now:** Android's supported networking model gives explicit tools for
network binding, bypass, and VPN upstream management. The product should model
those state changes directly instead of relying on implicit behavior.

**Current state:** `VpnService.protect()` integration lives in
`core/service/.../VpnProtectSocketServer.kt`, a Unix domain socket server that
accepts file descriptors and calls `VpnService.protect(fd)` on the service
side. Upstream binding is now centralized through
`core/service/.../VpnUpstreamNetworkBinding.kt` and
`RipDpiVpnService.syncUnderlyingNetworksFromActiveNetwork()`, which make the
no-`allowBypass()` / no-`bindSocket()` policy explicit. Handover processing now
tracks explicit state transitions in `ServiceRuntimeCoordinator.kt` and
surfaces them through service telemetry while `VpnServiceRuntimeCoordinator.kt`
resets resolver/failover state explicitly before restart.

**Primary areas:**
- `core/service/.../VpnProtectSocketServer.kt` (130 lines)
- `core/service/.../ServiceRuntimeCoordinator.kt` (handover processor)
- `core/service/.../VpnServiceRuntimeCoordinator.kt` (542 lines)
- `native/rust/crates/ripdpi-android/src/vpn_protect.rs`
- `core/engine/` (VPN lifecycle surface)
- Android VPN lifecycle and protect/socket binding integration

**Tasks**

- [x] Treat `VpnService.protect()` failure and VPN-rights revocation as
  first-class runtime events.
  Landed scope: the Unix-socket protect fallback now emits explicit
  `VpnProtectFailureEvent`s and returns a failing ack to native callers when
  `VpnService.protect()` rejects a socket, while VPN consent loss now halts the
  VPN path through explicit permission-failure handling in the coordinator and
  service shell.
- [x] Audit and harden upstream socket binding:
  - `protect()` usage
  - `bindSocket()` / network binding where appropriate
  - `setUnderlyingNetworks()` updates on handover
- [x] Add explicit handover-state transitions so strategy and resolver state can
  reset or revalidate on network change.
- [x] Audit `allowBypass()` / address-family bypass behavior for leakage risks.
- [x] Surface proxy-mode limitations that affect browser-native TLS/ECH
  behavior.
- [x] Add Android integration tests for:
  - Wi-Fi to cellular handover
  - VPN revocation
  - upstream-network loss
  - protect callback unavailability
  - proxy-mode compatibility
  Landed scope: focused service lifecycle integration coverage already existed
  for upstream loss and proxy-mode lifecycle paths, while Phase 14 adds
  explicit coordinator/reporter tests for protect failure, VPN consent loss,
  handover revalidation state, upstream-binding audit, and proxy-mode UI
  surfacing.

**Done when**

- Android network changes are visible to the runtime and diagnostics.
- Proxy-mode and VPN-mode differences are explicit product concepts.
- Unsupported platform behavior no longer masquerades as censorship behavior.

## Workstream 9: Measurement, Lab Tooling, And Rollout Gates

**Status:** [x] Complete
**Priority:** P2
**Why now:** New bypass work should ship only when backed by server acceptance,
device coverage, and detectability measurements. Otherwise the system becomes
more adaptive on paper and less predictable in practice.

**Current state:** `ripdpi-telemetry` is still 776 lines total (`lib.rs` 282,
`recorder.rs` 494) with `LatencyHistogram` / `LatencyPercentiles` snapshots
(lib.rs lines 68-77). Diagnostics export in `core/diagnostics/` now carries a
Phase 16 measurement surface through archive schema v4: `analysis.json` and
`telemetry.csv` record network identity bucket, target bucket, recommended
emitter tiers, inferred runtime capability gaps, acceptance metrics,
detectability metrics, and rollout-gate assessment. Static fixtures now exist
in `contract-fixtures/phase16_acceptance_corpus.json`,
`contract-fixtures/phase16_rollout_gates.json`, and
`contract-fixtures/phase16_lab_matrix.json`. The repeated environment matrix is
defined through `.github/workflows/phase16-matrix.yml`, driven by
`scripts/ci/phase16_matrix.py`, and executed through
`scripts/ci/run-phase16-matrix-entry.sh`. Lab capture/replay summarization is
now covered by `scripts/ci/phase16_pcap_summary.py`, which reads both host
packet-smoke captures and Android device captures into a shared summary format.

**Primary areas:**
- `native/rust/crates/ripdpi-telemetry/` (776 lines)
- `native/rust/crates/ripdpi-monitor/src/pcap.rs` (capture primitive)
- `core/diagnostics/` (Kotlin export pipeline)
- `.github/workflows/ci.yml` (relay emulator, TUN E2E, loom)
- `scripts/ci/` (baseline scripts, verification entry points)
- local measurement tooling and rooted lab flows

**Tasks**

- [x] Build a server-acceptance corpus that covers:
  - major CDNs
  - different TLS stacks
  - QUIC-capable and non-QUIC endpoints
  - ECH-advertising and non-ECH domains
- [x] Add a repeated test matrix over:
  - Wi-Fi and cellular
  - IPv4 and IPv6
  - rooted and non-rooted devices
  - proxy mode and VPN mode
- [x] Add detectability-oriented metrics in addition to reachability metrics.
- [x] Expand diagnostics exports so they include:
  - network identity bucket
  - emitter tier
  - capability snapshot
  - target bucket
  - acceptance / detectability metrics
- [x] Add lab-only packet-capture and replay tooling where it materially helps
  evaluate emitters.
- [x] Define rollout gates for new tactics:
  - acceptance threshold
  - latency budget
  - instability budget
  - detectability budget
  - Android compatibility budget

**Done when**

- Every new production tactic has acceptance and rollout evidence.
- Diagnostics reports contain enough context to reproduce a win or failure.
- Lab-only tools support research without expanding production complexity.

## Tactic Triage Matrix

### Keep And Strengthen

- [ ] TLS record fragmentation and record-boundary choreography
- [x] QUIC pre-Initial dummy datagrams
- [x] QUIC CRYPTO / SNI splitting
- [x] version-negotiation and version-shaped QUIC tactics backed by capability
  checks
- [ ] encrypted DNS failover as a product feature, but with confidence scoring

### Demote From Production Defaults

- [ ] non-root TTL-based disorder as a primary strategy
- [ ] generic fake packet families
- [ ] fixed duplicate-host fake flows
- [x] QUIC `FakeBurst`
- [x] QUIC CID churn without named evidence
- [x] QUIC packet-number gap tricks without named evidence

### Keep For Lab / Diagnostics Only

- [ ] malformed or low-acceptance TLS tricks
- [ ] fake-RST-heavy experiments
- [ ] exotic flag combinations without current field wins
- [ ] broad fragmentation permutations that lack acceptance data

## Sequencing Notes

- Workstream 1 must land before new tactic expansion, otherwise measurements
  remain untrustworthy.
- Workstream 2 should start before major TLS or QUIC feature additions so new
  work lands on the long-term planner architecture.
- Workstreams 3, 4, and 5 can overlap once the first-flight IR shape is stable.
- Workstream 6 depends on cleaner diagnostics from Workstreams 1 and 4.
- Workstream 8 should progress in parallel with Workstreams 1 and 4 because
  Android runtime state directly affects both emitter capabilities and resolver
  quality.
- Workstream 9 is continuous and should gate promotion from lab to production.

## First Implementation Slice

The first slice should be intentionally narrow:

1. Add explicit capability results and remove silent TTL degradation.
2. Reclassify TTL-dependent non-root tactics as opportunistic in diagnostics and
   candidate generation.
3. Replace DNS "no overlap = substitution" with richer outcome classes and
   oracle-health gating.
4. Define the first-flight IR boundary and migrate one representative TLS path
   and one QUIC path onto it.
5. Freeze addition of new production fake-packet variants until the IR and
   measurement gates are in place.

That slice reduces current risk without requiring a full rewrite upfront.
