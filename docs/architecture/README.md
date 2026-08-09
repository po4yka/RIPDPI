# RIPDPI Architecture Notes

This directory keeps compact architecture documentation. Completed history lives in git.

## Start here

The canonical architecture docs, in reading order:

1. [ARCHITECTURE.md](ARCHITECTURE.md) — the "start here" map: modules, runtime modes, control/data plane, config flow
2. [RUNTIME_MODES.md](RUNTIME_MODES.md) — proxy, VPN/TUN, diagnostics, relay, and root-helper runtime paths
3. [NATIVE_RUST.md](NATIVE_RUST.md) — native Rust workspace taxonomy and dependency direction
4. [JNI_CONTRACT.md](JNI_CONTRACT.md) — the Kotlin ↔ Rust JNI boundary contract
5. [CONFIG_CONTRACTS.md](CONFIG_CONTRACTS.md) — settings, protobuf, native JSON, and Rust config compatibility
6. [FEATURE_EXTENSION_GUIDE.md](FEATURE_EXTENSION_GUIDE.md) — how to add features safely

The notes below this point are compact, topic-specific records; the six docs above are the entry points.

Protocol decisions that should not be re-litigated from old plans are indexed in [../adr/README.md](../adr/README.md), including the VLESS Reality ECH policy and the Snowflake native Rust no-go decision.

## Contracts

Cross-boundary contract docs:

- [DIAGNOSTICS_ARCHITECTURE.md](DIAGNOSTICS_ARCHITECTURE.md) — diagnostics probe/candidate registration seams, raw-path vs in-path, lifecycle/policy-memory interaction
- [TELEMETRY_CONTRACT.md](TELEMETRY_CONTRACT.md) — runtime telemetry event/snapshot ownership, payload rules, Rust↔Kotlin forward-compatibility
- [ROOT_HELPER_CONTRACT.md](ROOT_HELPER_CONTRACT.md) — root-helper IPC protocol, command table, session nonce, fd passing, and the non-root fallback invariant

## Ownership Boundaries

**Config contract:** Kotlin is authoritative for user-facing strategy models, defaults, validation, and JSON serialization. Rust consumes the JSON produced by Kotlin. Owners: the `StrategyChain*.kt` files in `core/data/model` and `RipDpiProxyJsonCodec.kt`.

**Diagnosis classification:** Rust produces the final blocking verdict because packet, TLS, DNS, and timing evidence is collected in the native monitor. Kotlin maps the result to UI and persistence without re-classifying. Owner: `native/rust/crates/ripdpi-diagnostics-classification/src/classification/diagnosis.rs`.

**Service lifecycle:** abstract `BaseServiceRuntimeCoordinator` owns shared sequencing, restart/backoff, and stop/start orchestration. `ProxyServiceRuntimeCoordinator` and `VpnServiceRuntimeCoordinator` provide mode-specific orchestration through concrete subclasses and composed collaborators.

**Cloudflare tunnel mode:** Kotlin dispatches `consume_existing` versus `publish_local_origin`. Rust relay-core remains transport-only and intentionally does not branch on tunnel mode.

**Owned-stack browser route:** `OwnedStackBrowserScreen` is a remediation-only route opened from diagnostics actions. It is registered for navigation but intentionally excluded from bottom navigation and top-level settings.

## Desync And Relay Rules

**Emitter tiers:** Tactics are classified as `non_root_production`, `rooted_production`, or `lab_diagnostics_only`. Device capability checks decide whether an emitter can run; they do not change the tactic taxonomy.

**Tier-3 platform primitives:** Rooted/lab chain emitters are separate from experimental platform primitives. `MultiDisorder`, `IpFrag2`, `IpFrag2Udp`, and `FakeRst` are represented in typed chain settings and packet-smoke coverage; `root_mode_enabled` and capability checks decide whether rooted emitters may run. The lower-level SYN-hide and ICMP-wrapped UDP functions in `ripdpi-privileged-ops::experimental_tier3` are root-helper/platform internals only: they are not wired through `DesyncMode`, protobuf settings, or UI. `synack` / `synack_split` are a third path: strategy YAML can configure the TUN ingress SYN-ACK interceptor, while the strategy registry still treats them as descriptor-only `DesyncStrategy` placeholders. Promoting any experimental/TUN-ingress path into the general desync action pipeline requires schema, UI, packet-smoke, and security review work.

**`RelaySession::open_datagram`:** The API is live for relay implementations that support datagrams. The absence of broad call sites does not make it dead code.

**Finalmask UI exposure:** All finalmask runtime parameters are intentionally exposed as typed Mode Editor fields. `chain_dsl` is only the DPI strategy-chain DSL and must not absorb finalmask semantics.

**TLS profile catalog:** Edge and Safari profiles are intentionally compact profile constants. Edge is Chromium-derived but has distinct catalog identity; Safari carries distinct extension ordering, GREASE, supported-groups, record, and cipher preferences.

## Adaptive Runtime

**Direct-mode dispatcher:** Direct-path observations are converted into `DirectPathBlockClass`, then ranked transport arms. Attempt budgets and Config/Diagnostics/Home remediation selection use the same direct-mode result path.

**Offline learner:** UCB1 remains the production scorer. Thompson sampling exists as a standalone implementation; rarity penalties, attempt budgets, shared-priors transport, and environment segregation are opt-in or supporting pieces.

**Shared priors:** Bundles are fail-secure: parser, manifest verification, production key, and manifest/priors URLs must all validate before data reaches the evolver. Production release activation is tracked by `SVC-1786272083078316`.

**Environment calibration:** Android environment detection is wired for bandit key segregation. Privacy-clean sim-to-field calibration research is tracked by `TST-1786272277266667`.

## ECH Rotation

`ripdpi-diagnostics-dns` keeps ECH support fresh through `CdnEchUpdater`:

- bundled ECH bytes are always available as fallback;
- `RemoteEchConfigSource` fetches HTTPS-RR ECHConfigList data through the existing encrypted-DNS plumbing;
- malformed or missing remote data never clears the previous usable config;
- Kotlin persists refreshed bytes with `EncryptedSharedPreferences` and seeds the native updater on process start.

Current TLS consumers do not read the process-wide updater snapshot. The MASQUE path resolves per-target ECH directly through encrypted DNS via `resolve_outbound_ech_config_via_encrypted_dns`; the updater is a refresh/persistence cache until a future consumer is explicitly wired to it.

## io_uring

The io_uring path has two landed performance improvements:

- completion waiters use event-driven wakeup through `Thread::unpark`;
- TUN writes can stage packets into registered buffers and submit `WriteFixed`.

`native/rust/crates/ripdpi-io-uring/benches/io_uring_acceptance.rs` is the acceptance benchmark harness for plain write versus registered-buffer write paths across representative payload sizes.

## Additional Documents

- [first-flight-ir.md](first-flight-ir.md) — First-flight IR design.
- [hotspots.md](hotspots.md) — Performance hotspots reference.
- [quic-initial-packetizer.md](quic-initial-packetizer.md) — QUIC initial packetizer design.
- [native-runner-and-platform-decomposition.md](native-runner-and-platform-decomposition.md) — Connectivity runner split, diagnostics-probes compat facade, and `TcpDesyncPlatform` capability decomposition.
- [post-poy7-decomposition-gradient.md](post-poy7-decomposition-gradient.md) — Android adapter crate decomposition, runtime-adaptive policy sink, and Kotlin sub-service splits (`9884feef..d6f5f59f`); pins JNI-export-in-cdylib rule and dependency direction.
- [quality-gates.md](quality-gates.md) — Architecture quality gates.
