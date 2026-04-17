# RIPDPI Roadmap Execution Queue

Unified ordered slice list across the four active roadmaps, formatted for
oh-my-claudecode (OMC) orchestration. Each slice is sized to fit one OMC
session with an explicit verify gate.

Source roadmaps:
- [ROADMAP-architecture-refactor.md](../ROADMAP-architecture-refactor.md) (W0-W8)
- [ROADMAP-bypass-modernization.md](../ROADMAP-bypass-modernization.md) (W1-W9)
- [ROADMAP-bypass-techniques.md](../ROADMAP-bypass-techniques.md) (Tier 3 items 13-15)
- [docs/roadmap-integrations.md](roadmap-integrations.md) (6 continuous tracks)

## How To Use This Queue

1. Work phases in numeric order unless explicitly marked parallel.
2. Each slice lists an **OMC mode** recommendation:
   - `Ultrawork` -- independent tasks, parallel burst
   - `Team` -- plan -> prd -> exec -> verify -> fix pipeline
   - `Ralph` -- persistent verify/fix loop (safety-critical)
   - `Autopilot` -- end-to-end autonomous (low-risk slices only)
   - `/deep-interview` first -- architectural commitment before code
3. **Verify gate** must pass before the slice is considered complete. Failures
   go back through OMC's fix loop rather than being marked done.
4. The project-specific agents listed in `.claude/agents/` take precedence over
   OMC's generic specialists -- reference them in the verify gate column.
5. Do not chain more than one **Ralph**-gated slice into a single session; each
   Ralph slice is a commit boundary.
6. **No-backend rule:** this project will never have a backend. Slices marked
   as requiring a cooperating server (P15.1, P15.2) are inherently experimental
   and can only ship behind `root_mode_enabled` + experimental flag.

## Phase 0 -- Guardrails (parallel)

**Status: COMPLETE (2026-04-15).** All 6 slices landed via Ultrawork.
Commits: `2c38d5bb` `a98425a6` `c13c24ad` (ADRs), `8e88a182` (LoC reporter),
`f97a8861` (DisallowNewSuppression detekt rule), `dc2151e6` (Rust allow-guard
+ baseline), `4ac8d9a4` (module-dep guard), `13f77db8` + `619503ca`
(hotspots generator + determinism fix).

Mode: `Ultrawork`. Prerequisite for every other phase. These slices are
independent and safe to run concurrently.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 0.1 | Commit three ADRs under `docs/architecture/adr-001-config-contract.md`, `adr-002-diagnosis-classifier.md`, `adr-003-service-lifecycle.md` | - | Markdown review; each ADR names a single owner |
| 0.2 | Extend `scripts/ci/check_file_loc_limits.py` to emit top-5 longest functions per hotspot + suppression counts | - | Script runs on main; output matches hotspot inventory table |
| 0.3 | Detekt custom rule in `quality:detekt-rules` rejecting new `@Suppress` / `@file:Suppress` unless allowlisted with roadmap reference | - | `./gradlew detekt` fails on test fixture with new suppression |
| 0.4 | Clippy rule or lint script rejecting new `#[allow]` in production Rust outside baseline | - | Fails on test fixture; existing baseline still passes |
| 0.5 | Module-dependency guard `scripts/ci/verify_module_deps.py` mirroring `verify_diagnostics_boundary.py` pattern for `:app`, `:core:diagnostics`, `:core:service` | 0.2 | Snapshot current counts; fails on synthetic violation |
| 0.6 | Generator script + embedded `docs/architecture/hotspots.md` regenerated from live LoC counts | 0.2 | `docs/architecture/hotspots.md` regenerates deterministically |

## Phase 1 -- Config Contract (architecture W1)

**Status: COMPLETE (2026-04-17).** Slices 1.1-1.5 landed via Ralph, and
Phase 1b closed 1.6-1.8 in `52e4fe2c`.
Commits: `7bfd9a29` + `d5e6c9fb` (StrategyChains split + deletion),
`6421b0c6` + `10885de6` (Slice 1.0 prereq fixes for pre-existing
`:core:engine:detekt` debt -- ComplexCondition + LongParameterList
fixed at source, not suppressed), `0f6a2bb7` (codec split: ChainsCodec
285L + FakePacketCodec 163L; parent 1494->1063L), `08ddee78` (round-trip
harness Kotlin + Rust), `3d06ea31` (relay-heavy fixture covering MASQUE,
Cloudflare Tunnel, NaiveProxy with credential ref), `d0b87adf` (drop
TooManyFunctions suppression from StrategyChainValidation.kt), `52e4fe2c`
(`RipDpiProxyJsonCodec.kt` 345L orchestrator + remaining section codecs,
`convert.rs` 147L dispatcher + `convert/` builders, legacy payload adapter).

Mode: `Ralph`. Implements the "First Recommended Slice" of architecture-refactor.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 1.1 | Split `StrategyChains.kt` (822 code-only LoC) into model / DSL-parse / DSL-format / validation / protobuf-mapping files | Phase 0 | `./gradlew :core:data:test` + detekt on touched files |
| 1.2 | Split `RipDpiProxyJsonCodec.kt` section codecs for chains + fake-packet first | 1.1 | `./gradlew :core:engine:test` + round-trip JSON fixtures |
| 1.3 | Add round-trip test harness: AppSettings -> Kotlin model -> JSON -> Rust UI payload -> runtime config | 1.2 | New tests pass; `rust-test-runner` for native side |
| 1.4 | Golden fixtures for one chain-heavy + one relay-heavy config | 1.3 | Fixtures committed; round-trip tests compare stable output |
| 1.5 | Remove at least one blanket suppression from files touched in 1.1-1.2 | 1.1 | Detekt baseline unchanged elsewhere |
| 1.6 | Complete: split `RipDpiProxyJsonCodec.kt` remaining sections (listen/protocols, adaptive fallback, relay/warp/ws-tunnel, runtime context) | 1.2 | Codec object remains thin; section tests pass |
| 1.7 | Complete: split `convert.rs` (1464 LoC) into section builders: listen, protocol, chain, fake-packet, relay, warp, adaptive | 1.6 | `cargo test -p ripdpi-proxy-config`; LoC baseline shrinks |
| 1.8 | Complete: isolate legacy payload compatibility into dedicated adapter | 1.7 | Main config path has no compat branches; adapter tested independently |

## Phase 2 -- Capability Hygiene (bypass-modernization W1)

**Status: COMPLETE (2026-04-15 to 2026-04-16).** All 7 slices landed via Ralph.
Commits: `ece113a8` (RuntimeCapability + CapabilityOutcome types),
`056cacdb` (TTL outcomes via `try_set_stream_ttl_with_outcome`),
`6f113f0e` (~20 candidates tagged TtlWrite, 1 RawTcpFakeSend, 1
RootHelperAvailable + `enumerate_capable_candidates` filter),
`de09c65d` (`FailureClass::CapabilitySkipped` + Kotlin
`DiagnosticsOutcomeBucket.Inconclusive` mapping +
`record_capability_skipped` telemetry recorder),
`65fea440` (5 regression tests pinning TTL/protect/raw-path outcomes),
`81fcba05` (evolver `record_failure(CapabilitySkipped)` short-circuits;
3-skipped + 1-success ranks identical to 1-success), `55b1aab4` (split
into primary / opportunistic / rooted pools; probe matrix preserved
across HTTP/TLS/TLS-ECH/QUIC v1/QUIC v2; `tlsrec_hostfake_split` and
`tlsrec_hostfake_random` stay in primary via graceful-fallback allowlist).

Mode: `Ralph`. Implements the "First Implementation Slice" of bypass-modernization.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 2.1 | Define `Capability` enum + structured result type in `ripdpi-runtime/src/platform/mod.rs` covering TTL, raw TCP fake-send, raw UDP/fragmentation, replacement-socket, root-helper, protect callback, network binding | Phase 0 | `cargo test -p ripdpi-runtime`; no silent `io::Error` propagation for capability paths |
| 2.2 | Remove silent TTL degradation in `platform/linux.rs`; failures return structured capability outcome | 2.1 | `packet-smoke-debugger` on TTL-dependent strategies confirms explicit failures |
| 2.3 | Reclassify non-root TTL-dependent tactics as opportunistic in `ripdpi-monitor/src/candidates.rs` | 2.2 | Candidate enumeration test; capability-blind winners no longer promoted |
| 2.4 | Capability-skipped runs marked distinctly in diagnostics + telemetry | 2.3 | `core/diagnostics` test asserts new outcome class |
| 2.5 | Regression tests for TTL-fail, protect-fail, raw-path-unavailable never collapse to generic "network blocked" | 2.4 | New tests pass; `regression-detector` baseline comparison |
| 2.6 | Strategy learning excludes failed-emitter runs from ranking in `strategy_evolver.rs` | 2.5 | Unit test on evolver; ranking unchanged when emitter-failed runs are dropped |
| 2.7 | Demote fake-RST-heavy flows + fixed-duplicate `HostFake` + generic `Disorder`/`Fake` from primary candidate sets without capability pre-check | 2.3 | Candidate enumeration diff; probe matrix still viable |

## Phase 3 -- Native Runtime Decomposition (architecture W3)

**Status: COMPLETE (2026-04-17).** The earlier executor extractions are now
joined by the remaining lowering/capability split in `runtime/tcp_lowering.rs`,
the UDP flow extraction into `runtime/udp/flow.rs`, and the platform
capability / IPv4-identification submodules under `platform/`. `desync.rs`
still needs future architectural pressure from later phases, but the specific
Workstream 3 seams that blocked planner and packetizer work are now in place.

Mode: `Ralph`. Critical path -- unblocks Phases 4, 5, 13.

| Slice | Status | Scope | Depends on | Verify gate |
|-------|--------|-------|------------|-------------|
| 3.1 | Complete | Extract the central TCP plan branching into focused executor helpers and a smaller dispatcher/control loop | Phase 0, Phase 2 | `cargo test -p ripdpi-runtime`; targeted runtime regressions green |
| 3.2 | Complete | Extract fake/fakedsplit/fakeddisorder family executor | 3.1 | Packet smoke across fake families; `rust-test-runner` |
| 3.3 | Complete | Extract hostfake family executor | 3.1 | Packet smoke across hostfake variants including midhost |
| 3.4 | Complete | Extract disorder/disoob/oob/basic-stream and TTL-sensitive family executors | 3.1 | Packet smoke; `unsafe-code-auditor` if raw-path code moves |
| 3.5 | Complete | Extract fragmentation / `FakeRst` tails and finish flag-override executor cleanup | 3.1 | Packet smoke for fragmentation + flag masks |
| 3.6 | Complete | Move Android TTL fallback into dedicated lowering layer (consumes Capability type from 2.1) | 3.2, 3.3, 3.4, 3.5 | Capability snapshot is single source of truth; no scattered capability checks |
| 3.7 | Complete | Introduce typed per-connection capability snapshot passed into lowering | 3.6 | `cargo test -p ripdpi-runtime`; signature review |
| 3.8 | Complete | Decompose `udp.rs` into orchestration plus focused flow/actions/codec/socket modules | 3.1 | `cargo test`; QUIC smoke tests unchanged |
| 3.9 | Complete | Split `platform/linux.rs` and `platform/mod.rs` along fake-send / fragmentation / capability-dispatch boundaries | 3.7 | `jni-bridge-verifier` if JNI touched; `native-verifier` for .so size regression |
| 3.10 | Complete | Expand regression tests covering extracted step-family executors in isolation | 3.2-3.5 | `coverage-reporter` confirms family coverage |

## Phase 4 -- First-Flight IR (bypass-modernization W2)

**Status: COMPLETE (2026-04-17).** The Phase 4 foundation and the remaining
planner migration are landed: `docs/architecture/first-flight-ir.md`, IR types
and normalizers in `ripdpi-desync::first_flight_ir`, additive layout surfaces
in `ripdpi-packets`, IR-driven TLS prelude record fragmentation, IR-backed TCP
semantic offset resolution through `proto.rs`, semantic fake-family handling
after TLS prelude, IR-seeded QUIC UDP prelude builders, planner removal of the
seqovl capability downgrade, terminal fake-step lowering migration, and the
dedicated rewrite goldens.

Mode: `Team` with `/deep-interview` on 4.1. Must not start on old monolith.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 4.1 | Complete | `/deep-interview` to pin down IR module boundary + invariants; commit design doc `docs/architecture/first-flight-ir.md` | Phase 1, Phase 3 | Design review; invariants explicit |
| 4.2 | Complete | Implement IR types covering SNI/host, ALPN, TLS record boundaries, QUIC CRYPTO layout, GREASE, ECH, desired datagram/segment boundaries | 4.1 | `cargo test -p ripdpi-desync`; type-level invariants checked |
| 4.3 | Complete | Normalize TLS ClientHello into IR (consume `tls_client_hello_marker_info_in_handshake`) | 4.2 | TLS fixture round-trip preserves IR |
| 4.4 | Complete | Normalize QUIC Initial into IR (consume `parse_quic_initial`) | 4.2 | QUIC fixture round-trip preserves IR |
| 4.5 | Complete | Migrate `TlsRec`, `TlsRandRec`, split, fake logic onto IR | 4.3 | `packet-smoke-debugger` TLS scenarios unchanged |
Current landed scope: `tls_prelude.rs` now uses the IR for record splitting and carries semantic proto state forward; `proto.rs` resolves TLS semantic offsets (`Host`, `MidSld`, `EndHost`, `SniExt`, `ExtLen`, `EchExt`) from IR-backed `TlsProtoInfo`; and fake-family helpers now classify prelude-rewritten TLS payloads semantically instead of falling back to raw `is_tls_client_hello()` checks.
| 4.6 | Complete | Migrate QUIC prelude logic onto IR | 4.4 | `packet-smoke-debugger` QUIC scenarios unchanged |
Current landed scope: `plan_udp.rs` routes QUIC SNI split, dedicated CRYPTO split, realistic fake-burst generation, `QuicMultiInitialRealistic` default host/version selection, padding ladder, CID churn, packet-number-gap, and version-negotiation decoy seed packets through the normalized QUIC IR instead of defaulting to arbitrary payload-local bytes.
| 4.7 | Complete | Move terminal-step and emitter-specific restrictions from planner to lowering | 4.5, 4.6 | Planner has no emitter-specific branches |
Current landed scope: terminal `FakeSplit` / `FakeDisorder` degradation stays semantic in `plan_tcp.rs`; runtime routing in `ripdpi-runtime::runtime::desync` owns terminal fake-step fallback; and planner-owned seqovl capability downgrade is removed so lower/execution fallback, not semantic planning, owns unsupported-emitter behavior.
| 4.8 | Complete | Golden fixtures: TLS record fragmentation, ALPN changes, QUIC CRYPTO splits, ECH/GREASE-preserving rewrites | 4.5, 4.6 | Golden-blesser workflow; fixtures committed |
Current landed scope: `ripdpi-desync/tests/golden/phase4_tls_record_fragmentation.json`, `phase4_tls_alpn_preservation.json`, `phase4_quic_crypto_split.json`, and `phase4_tls_ech_grease_preservation.json` now pin the IR-visible rewrite outputs for TLS fragmentation, ALPN-preserving fragmentation, QUIC CRYPTO split layout, and ECH/GREASE-preserving TLS fragmentation.

## Phase 5 -- QUIC Initial Shaping Subsystem (bypass-modernization W3)

Current status (2026-04-17): complete. `docs/architecture/quic-initial-packetizer.md`
defines the QUIC Initial packetizer boundary and layout families;
`ripdpi-packets` now exposes `QuicInitialSeed`, `QuicInitialPacketLayout`,
packetizer/parser helpers, and browser-like QUIC builders; `plan_udp.rs` routes
the production QUIC layout tactics through the packetizer; the production probe
pool demotes `FakeBurst`, `QuicCidChurn`, and `QuicPacketNumberGap`; and
strategy-probe exports record the exact winning QUIC layout family end to end.

Mode: `Team` with `/deep-interview` on 5.1.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 5.1 | Complete | `/deep-interview` on QUIC Initial packetizer design -- CRYPTO offsets, frame ordering, PADDING, datagram count, coalescing, 1200-byte expansion, v1/v2 selection | Phase 4 | Design doc + golden fixture plan |
| 5.2 | Complete | Implement QUIC Initial packetizer in `ripdpi-packets` | 5.1 | `cargo test -p ripdpi-packets`; fixture-verified wire output |
| 5.3 | Complete | Rework `DummyPrepend`, `QuicSniSplit`, `QuicCryptoSplit`, `QuicMultiInitialRealistic` onto packetizer | 5.2 | `packet-smoke-debugger`; behavior unchanged |
| 5.4 | Complete | Add browser-like QUIC profiles (Chrome/Firefox Android) | 5.3 | Strategy pack fixture; no fingerprint regression |
| 5.5 | Complete | Demote `FakeBurst`, `QuicCidChurn`, `QuicPacketNumberGap` from production defaults to lab | 5.3 | Candidate enumeration diff; diagnostics-export change logged |
| 5.6 | Complete | Server capability probes: QUIC v1/v2 support, compatible version negotiation, handshake acceptance under split/coalesced Initial layouts | 5.3 | Probe candidates added to `ripdpi-monitor/src/candidates.rs` |
| 5.7 | Complete | Extend diagnostics exports: QUIC winners record exact Initial layout family | 5.6 | Export round-trip test |

## Phase 6 -- DNS Oracle Hardening (bypass-modernization W4)

Mode: `Ralph`. Parallel-safe with Phase 4/5 after Phase 2 lands.

Current status (2026-04-17): complete. `ripdpi-monitor` records multi-oracle
trust/confidence and refuses to classify fallback-only encrypted DNS answers as
trusted oracle evidence. `ripdpi-dns-resolver` now carries oracle-aware health,
resolver quarantine, and network-scoped ranking/fallback memory, with focused
variance coverage including CDN-style partial overlap and IPv4/IPv6-separated
scope history. The former DDR/HTTPS/SVCB discovery item is no longer a gating
slice; treat it as future enhancement work if bootstrap evidence shows a real
need.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 6.1 | Replace `dns_substitution` single no-overlap rule with richer outcome classes: exact, compatible divergence, suspicious divergence, sinkhole, NXDOMAIN mismatch, oracle unavailable | Phase 2 | Unit tests across all outcome classes |
| 6.2 | Multi-oracle confidence scoring wired into resolver-policy ranking and quarantine | 6.1 | Multi-resolver disagreement test |
| 6.3 | Network-identity-keyed resolver ranking (wifi/cellular x ipv4/ipv6 x operator x transport) | 6.2 | Network-scoped ranking tests |
| 6.4 | Oracle-health gating before using encrypted result as diagnostic reference | 6.2 | Quarantine test; failures do not retrain strategy system |
| 6.5 | DDR/HTTPS/SVCB-driven resolver discovery (stretch, non-gating) | 6.3 | Integration test with mock HTTPS record |
| 6.6 | Hedged queries restricted to bootstrap/failover/diagnostics only | 6.2 | Sequential steady-state exchange remains intact |
| 6.7 | Test suite: CDN variance, IPv4/IPv6 asymmetry, partial overlap, multi-resolver disagreement | 6.1-6.4 | `ripdpi-dns-resolver` unit suite |

## Phase 7 -- Service + Relay Decomposition (architecture W4)

Mode: `Team`. Parallel-safe with Phase 6.

Current status (2026-04-17): complete. Relay runtime config shaping is split
behind per-kind resolvers and focused tests, while service runtime
coordination now routes lifecycle state, handover monitoring/retry,
permission-watch collection, telemetry-loop ownership, and the stable shared
proxy-runtime seam through smaller collaborators.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 7.1 | Split `BaseServiceRuntimeCoordinator` lifecycle state machine + handover processor | Phase 0 | `kotlin-design-auditor`; existing service tests pass |
| 7.2 | Split retry/backoff policy + permission watchdog + telemetry loop | 7.1 | Focused unit tests per collaborator |
| 7.3 | Split `UpstreamRelaySupervisor.resolveRuntimeConfig()` into per-relay-kind resolvers: MASQUE, Snowflake, Cloudflare Tunnel, ShadowTLS, NaiveProxy, Chain relay, local SOCKS/TUIC/Hysteria variants | Phase 1 | Relay-kind validation tests per resolver |
| 7.4 | Introduce `RelayResolver` interface so per-kind validation + credential rules + defaulting live in one place | 7.3 | Interface tests; `arch-layer-auditor` |
| 7.5 | Extract shared runtime start/stop logic where seam is stable (do not force false sharing) | 7.1, 7.3 | VPN + proxy both green |

## Phase 8 -- Diagnostics Bounded Context (architecture W2)

**Status: COMPLETE (2026-04-17).** Rust-engine diagnosis authority now owns
final diagnoses during scan finalization, `DiagnosticsArchiveRenderer` has
split JSON / CSV builder seams under `export/`, diagnostics contracts are split
across `model/` files, and the home-audit workflow now lives in
`workflow/DiagnosticsHomeWorkflowServiceImpl.kt` with focused collaborators.
Verification: `./gradlew :core:diagnostics:testDebugUnitTest --rerun-tasks`.

Mode: `Team`. Parallel-safe with Phases 6-7.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 8.1 | Finalize classification authority decision from ADR 0.1; remove dual-classification from scan finalization | 0.1 | Single source of truth for diagnosis codes |
| 8.2 | Split `DiagnosticsArchiveRenderer.kt` (1188 code-only LoC, 48% over budget) into JSON / CSV / integrity-provenance / redaction / attachment-packer builders | 8.1 | `golden-blesser` for archive fixtures; renderer tests |
| 8.3 | Split `Models.kt` by concern: core scan/report, home-audit, export/share, projection | 8.1 | `kotlin-design-auditor` |
| 8.4 | Extract `DefaultDiagnosticsHomeWorkflowService` from services file into workflow package with smaller collaborators: recommendation applier, resolver action coordinator, capability evidence summarizer, audit outcome builder | 8.3 | Focused tests per collaborator |
| 8.5 | Package reorg of `:core:diagnostics` into `domain/` `application/` `finalization/` `recommendation/` `export/` `presentation/` `queries/` | 8.4 | `verify_diagnostics_boundary.py` passes; module-dep count unchanged |
| 8.6 | Test proving one classification source owns final diagnosis for both stored sessions and live scans | 8.1 | `coverage-reporter` for classification paths |

## Phase 9 -- UI Decomposition (architecture W5 + W6)

Mode: `Team`. Gated by Phase 1.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 9.1 | Split `AdvancedSettingsBinder.kt` (775 code-only LoC) into section-scoped mutation writers: connectivity/listen, desync/chains, fake-packet, QUIC, DNS, relay/warp, diagnostics, security | Phase 1 | `compose-audit` run; section tests |
| 9.2 | Replace giant global handler maps with typed section registries | 9.1 | Recomposition scope shrinks |
| 9.3 | Split `DesyncSection.kt` into section cards: chain editor, adaptive split, TCP flags, hostfake/seqoverlap, fake ordering, fake payload library, fake TLS, OOB/adaptive fake TTL | 9.1 | Visual + DSL editors separated; per-section tests |
| 9.4 | Split DNS settings route / section renderers / actions / validation helpers | 9.1 | DNS settings section tests |
| 9.5 | Split `HomeScreen.kt` (1314 code-only LoC, 31% over) into route/host + banners + connection card + analysis sheets + telemetry cards + helper animations | Phase 0 | `compose-audit` stability report |
| 9.6 | Split `DiagnosticsScreen.kt` (1254 code-only LoC, 25% over) into route/effect host + pager shell + section coordinators + section renderers + debug panel | Phase 0 | `compose-audit` |
| 9.7 | Split `DnsSettingsScreen.kt` (1437 code-only LoC, 44% over) into route shell + section composables | 9.4 | `compose-audit` |
| 9.8 | Compose compiler reports validated after each split; no new stability regressions | 9.5-9.7 | Compiler report committed as baseline |

## Phase 10 -- ViewModel Shaping (architecture W7)

Mode: `Team`. Parallel-safe with Phase 9.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 10.1 | Mirror `MainViewModel` grouping pattern for `DiagnosticsViewModel` | Phase 0 | Unit tests for state assemblers |
| 10.2 | Mirror grouping for `SettingsViewModel` | 10.1 | Same |
| 10.3 | Move long `combine` pipelines into assembly classes with narrow inputs | 10.1, 10.2 | Assemblers testable without UI shells |
| 10.4 | Move bootstrap/init work into explicit bootstrapper collaborators; separate effect emission from state assembly | 10.3 | Focused ViewModel tests |

## Phase 11 -- TLS Templates + ECH (bypass-modernization W5)

Mode: `Team`. Gated by Phase 4 + 5.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 11.1 | Extension order/permutation families + GREASE placement controls | Phase 4 | `packet-smoke-debugger`; fingerprint diff vs target browser |
| 11.2 | Supported-groups + key-share shaping + ALPN-aware templates | 11.1 | Fingerprint coverage matrix |
| 11.3 | Record-size choreography | 11.1 | `packet-smoke-debugger` |
| 11.4 | HelloRetryRequest-oriented tactics (when server behavior makes useful) | 11.2 | Server-acceptance corpus hit rate |
| 11.5 | ECH + DNS bootstrap integration: planning together, not as afterthought | Phase 6 | ECH-advertising fixture test |
| 11.6 | Android ECH API integration: availability check, config bootstrap, policy gating, fallback | 11.5 | Android instrumentation test |
| 11.7 | Detect + surface proxy-mode interactions suppressing browser-native ECH | 11.6 | Proxy-mode integration test |
| 11.8 | Replace generic fake packet families with coherent client-profile families; keep random-fake for lab only | Phase 5 | Candidate diff reviewed |
| 11.9 | Server-acceptance corpus for every new TLS template family across major CDNs and stacks | 11.1-11.4 | Acceptance rate above defined threshold in 16.5 |
| 11.10 | Strategy-pack catalog entries for new templates (bridge to integrations) | 11.9 | Pack signed and rolled through staged release |

## Phase 12 -- Learning Redesign (bypass-modernization W6)

Mode: `Team`. Gated by Phase 2 + Phase 6.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 12.1 | Replace one-domain qualifier round with stratified pilot evaluation | Phase 2 | Winner-selection invariant tests |
| 12.2 | Target bucket definitions: CDN family, TLS/QUIC, ALPN class, ECH-capable, reachability set | 12.1 | Bucket enumeration committed |
| 12.3 | Niche-winner pools instead of permanent pruning | 12.2 | Evolver test showing niche retention |
| 12.4 | Expanded reward signals: success rate, added RTT, stability, variance, energy cost, detectability | 12.3 | Reward unit tests |
| 12.5 | Hierarchical contextual bandit: family first, parameters second, reset on network change | 12.4 | `strategy_evolver` test covering family/param hierarchy |
| 12.6 | Feed network identity, transport capability, ALPN, ECH, resolver health, root/non-root into learning context | 12.5, Phase 6 | Integration test |
| 12.7 | Version exported diagnostics so methodology changes do not confuse old/new reports | 12.4 | Export version bumped; reader compat tests |

## Phase 13 -- Emitter Tier Rationalization (bypass-modernization W7)

Mode: `Team` with `/deep-interview` on 13.1. Gated by Phase 2 + Phase 7.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 13.1 | `/deep-interview` + ADR defining three emitter tiers: non-root production, rooted production, lab/diagnostics only | Phase 2 | ADR committed |
| 13.2 | Audit all 23 existing step kinds into tiers | 13.1 | Tier classification committed |
| 13.3 | Move exotic/weak-evidence tactics to lab-only: fake-RST experiments, generic weird-flag combos, broad fragmentation permutations | 13.2 | Candidate generation diff |
| 13.4 | Planner chooses tactic description; emitter tier decides realization | 13.2, Phase 3 | Planner/emitter separation test |
| 13.5 | UI + diagnostics labeling when tactic required root or was downgraded from exact to approximate | 13.4 | Screenshot test |

## Phase 14 -- Android Hardening (bypass-modernization W8)

Mode: `Team`. Parallel-safe with Phase 11-13.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 14.1 | Treat `VpnService.protect()` failure + VPN rights revocation as first-class runtime events | Phase 7 | Android instrumentation test |
| 14.2 | Audit upstream socket binding: `protect()`, `bindSocket()`, `setUnderlyingNetworks()` on handover | 14.1 | Audit doc + runtime log assertions |
| 14.3 | Explicit handover-state transitions so strategy + resolver state can reset or revalidate | 14.1 | Wifi-to-cellular integration test |
| 14.4 | Audit `allowBypass()` + address-family bypass for leakage | 14.2 | Leakage scenario test |
| 14.5 | Surface proxy-mode limitations affecting browser-native TLS/ECH | Phase 11 | User-facing notice in UI |
| 14.6 | Android integration tests: wifi-to-cellular, VPN revocation, upstream loss, protect unavailable, proxy-mode compat | 14.1-14.4 | `android-test-runner` suite passes |

## Phase 15 -- Tier 3 Tactics (bypass-techniques)

Mode: `Autopilot` acceptable (experimental + flag-gated). Gated by Phase 13.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 15.1 | SYN-Hide: new `CMD_SEND_SYN_HIDE_TCP` IPC handler in `ripdpi-root-helper` (~140 LoC). Client-side only; server component is out of scope per no-backend rule | 13.1 (lab tier) | `packet-smoke-debugger`; opt-in behind `root_mode_enabled` + experimental flag |
| 15.2 | UDP-over-ICMP: `CMD_SEND_ICMP_WRAPPED_UDP` / `CMD_RECV_ICMP_WRAPPED_UDP` + ICMP socket wrapper (~450 LoC). Requires cooperating server (user-provided; no project backend) | 13.1 (lab tier) | Root-path smoke test with mock server fixture |
| 15.3 | `docs/server-hardening.md` documenting nginx stream + graylist for users who self-host. Documentation only | - | Markdown review |

## Phase 16 -- Measurement + Rollout Gates (bypass-modernization W9)

Mode: `Team`, continuous. Run in parallel with execution phases.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 16.1 | Server-acceptance corpus: major CDNs, TLS stacks, QUIC-capable + non-QUIC, ECH + non-ECH (shared with 11.9) | - | Corpus committed as static fixture (no backend) |
| 16.2 | Test matrix automation over wifi/cellular x ipv4/ipv6 x rooted/non-rooted x proxy/vpn | Phase 14 | Matrix job in `.github/workflows/` |
| 16.3 | Detectability-oriented metrics beyond reachability | Phase 5, Phase 11 | Metrics emitted to diagnostics export |
| 16.4 | Expanded diagnostics exports: network identity bucket, emitter tier, capability snapshot, target bucket, acceptance/detectability metrics | 12.7, 13.2 | Export round-trip + schema version test |
| 16.5 | Rollout gate thresholds: acceptance, latency budget, instability budget, detectability budget, Android compat budget | 16.1-16.4 | Gate definitions committed; applied to next new tactic |

## Phase 17 -- Module Extraction (architecture W8)

Mode: `Ultrawork`. Gated by Phases 1, 4, 8, 13.

| Slice | Scope | Depends on | Verify gate |
|-------|-------|------------|-------------|
| 17.1 | Evaluate boundary stability across extracted seams; prune unstable candidates | 1, 4, 8, 13 | Boundary review doc |
| 17.2 | Extract diagnostics export/archive module | 8.2 | `verify_diagnostics_boundary.py` extended |
| 17.3 | Extract diagnostics application/finalization module | 8.4 | Module-dep guard unchanged |
| 17.4 | First-flight IR crate (if Phase 4 seam stable) | 4.7 | `cargo tree -p ripdpi-desync` clean |
| 17.5 | Emitter/capability crate (if Phase 13 seam stable) | 13.4 | Same |
| 17.6 | Dependency-boundary CI checks enforcing all extracted boundaries | 17.2-17.5 | CI fails on synthetic violation |

## Track S -- Integrations Validation (continuous)

Mode: `omc team :codex/:gemini` for parallel cross-transport validation, or
`Ultrawork` on in-repo fixtures. Runs in parallel with phases above.

| Slice | Scope | Verify gate |
|-------|-------|-------------|
| S.1 | MASQUE endpoint sampling + H3-to-H2 fallback telemetry validation | `ripdpi-masque` tests + field-validation report |
| S.2 | Cloudflare Tunnel publish-mode crash telemetry + operator docs | `CloudflarePublishRuntime` tests + ops doc committed |
| S.3 | Finalmask `noise` mode parity tests against upstream | New mode + enum entry in `finalmask.rs`; parity fixture |
| S.4 | NaiveProxy auth compatibility matrix + watchdog tuning | Matrix results in `docs/native/relay-naiveproxy-decision.md` |
| S.5 | TLS catalog refresh cadence documented + enforced | Scheduled workflow + rotation log |
| S.6 | Relay interop CI gap closure | `scripts/ci/run-rust-relay-interoperability.sh` coverage diff |

## Dependency Graph Summary

```
Phase 0 (guardrails) ---+-> Phase 1 (config) ---+-> Phase 9 (UI)
                        |                       |
                        +-> Phase 2 (capability) +-> Phase 3 (runtime) ---+-> Phase 4 (IR) -+-> Phase 5 (QUIC) -+
                        |                                                 |                                     |
                        +-> Phase 6 (DNS) -------------------------------> Phase 12 (learning) <----------------+
                        |                                                                                        |
                        +-> Phase 7 (service) ---+-> Phase 13 (tiers) -----+-> Phase 15 (Tier 3)                |
                        |                                                                                        |
                        +-> Phase 8 (diagnostics)                                                                |
                        +-> Phase 10 (ViewModel)                                                                 |
                        +-> Phase 14 (Android) ---------------------------------------------------------------+  |
                        +-> Phase 11 (TLS templates) <--------------------------------------------------------+
                        +-> Phase 16 (measurement, continuous) <--- all phases
                        +-> Phase 17 (module extraction, after stable seams)
                        +-> Track S (integrations, continuous, parallel)
```

## Priority Entry Points

**Phases 0 + 1 + 2 + 3 + 4 + 5 + 6 + 7 complete.**

Next OMC kickoff prompts in priority order:

1. `/ralph "begin Phase 11: replace generic TLS fake/template families with browser-family template catalogs and ship the first ECH-aware template path. Gate on cargo test -p ripdpi-packets, cargo test -p ripdpi-desync, focused diagnostics export coverage, and strategy-pack fixture review."`
2. `/ralph "/deep-interview Phase 11 from docs/roadmap-execution-queue.md: pin the browser-family TLS template boundary, catalog ownership, and ECH invariants before adding new template families."`
3. `/ralph "after the first browser-family template lands, align integrations catalog freshness and rollout metadata with the new TLS family boundary instead of adding hardcoded defaults."`
