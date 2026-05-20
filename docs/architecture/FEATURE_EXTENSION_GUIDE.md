# Feature Extension Guide

How to add a feature to RIPDPI **without breaking proxy, VPN, diagnostics, root,
or relay behavior**. Each section is a task checklist: the files to touch, the
current extension path, the compatibility gates, and the mistakes that recur.

Read [`ARCHITECTURE.md`](ARCHITECTURE.md) and [`NATIVE_RUST.md`](NATIVE_RUST.md)
first — this guide assumes the module map and crate layering from those docs.

This guide describes the codebase **as it is today**. Where an extension has no
clean abstraction and requires editing several call sites by hand, that is
called out as a **Future improvement**, not papered over.

---

## Universal gates — apply to every change

These hold regardless of which section you are in. A change that skips one of
these breaks something downstream:

- **`--locked` on every `cargo` invocation.** No silent dependency drift.
  See [`.claude/rules/rust-toolchain-pin.md`](../../.claude/rules/rust-toolchain-pin.md).
- **Never bless goldens to "fix" a failing test.** Investigate first.
  `RIPDPI_BLESS_GOLDENS=1` is human-only. See
  [`.claude/rules/golden-bless-discipline.md`](../../.claude/rules/golden-bless-discipline.md).
- **Never extend a baseline** (detekt, lint, LoC). Fix the violation. The
  PreToolUse hook blocks edits to `*baseline*` files.
- **Locale sync.** Any new key in `app/src/main/res/values/strings.xml` or
  `core/service/src/main/res/values/strings.xml` must land in all 6 other
  locale files in the same commit (`lint.xml` sets `MissingTranslation` to
  `error`).
- **`VpnService.protect()` invariant.** Any new non-loopback `TcpStream`/
  `UdpSocket`/`mio` socket in Rust must be protected before `connect`/`bind`.
  See [`.claude/rules/vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md).
- **No backend server.** Features work offline/locally; external data is static
  files or bundled assets only.
- **Non-rooted baseline.** The app must fully function without root; root-only
  capability is opt-in behind `root_mode_enabled` and degrades gracefully.
- **JNI containment.** Only the 12 L8 Android crates may touch `jni` /
  `android-support` (see [`NATIVE_RUST.md`](NATIVE_RUST.md) §5). New core logic
  stays JNI-free.
- **Config contract direction.** Kotlin owns user-facing models, defaults,
  validation, and JSON serialization; Rust consumes the JSON. Never let Rust
  re-derive a user setting.

---

## 1. Add a desync / TCP / TLS / QUIC strategy

### Files / crates likely touched

| Side | Location |
|------|----------|
| Proto | `core/data/model/src/main/proto/app_settings.proto` — `StrategyTcpStep` / `StrategyUdpStep` (new *per-step parameter* only; a new step *kind* is a free-form string and needs no proto field) |
| Kotlin model | `core/data/model/src/main/kotlin/com/poyka/ripdpi/data/StrategyChain{Model,Protobuf,Parser,Validation,Dsl}.kt`; defaults in `core/data/settings/.../DefaultStrategyChains.kt` |
| Kotlin → native | `core/engine/.../core/RipDpiProxyJsonCodec.kt` and `core/engine/.../core/codec/ChainsCodec.kt` (TCP/UDP), `FakePacketCodec.kt`; preference mappers `NativeProxyDesyncPreferencesMapper.kt` / `NativeProxyQuicPreferencesMapper.kt` |
| Rust config model | `native/rust/crates/ripdpi-config/src/model/tcp.rs`, `model/udp.rs`, `model/group.rs` |
| Rust planning | `native/rust/crates/ripdpi-desync` (chain planning), `ripdpi-strategy-*` impl crates registered via `ripdpi-strategy-registry` |
| Rust execution | `native/rust/crates/ripdpi-desync-runtime`; TUN-egress mutation in `ripdpi-tunnel-intercept` |
| TLS profiles | `native/rust/crates/ripdpi-tls-profiles` (for a `tls_fake_profile` / `tls_fingerprint_profile` catalog entry) |

### Current extension path

- **A new TCP chain step kind** (`split`, `disorder`, `tlsrec`, … — 13 today)
  is a **string** in `StrategyTcpStep.kind`. Adding one means handling that
  string in the Rust `ripdpi-config` model, the `ripdpi-desync` planner, and
  the `ripdpi-desync-runtime` executor, plus the Kotlin
  `StrategyChainParser`/`Validation`/`Dsl` and `ChainsCodec`. There is **no
  central step-kind plugin registry** — the string is matched in each layer by
  hand. *Future improvement: a single step-kind registry would remove the
  multi-site `match`.*
- **A new per-step parameter** (e.g. another field like `fake_seq_mode`) **does**
  need a new proto field number in `StrategyTcpStep`/`StrategyUdpStep` — see §5
  rules for field-number discipline.
- **A new TLS fake / fingerprint profile** is a catalog entry in
  `ripdpi-tls-profiles` plus a new allowed string for `tls_fake_profile`
  (proto field 89) / `tls_fingerprint_profile` (204).
- **QUIC** variation uses the dedicated `quic_*` proto fields and the QUIC
  handling in `ripdpi-desync`; see
  [`architecture/quic-initial-packetizer.md`](quic-initial-packetizer.md).
- To make a strategy **probeable** by diagnostics, also add it as a candidate
  (see §3) — the 24 TCP + 6 QUIC candidates are listed in
  [`AGENTS.md`](../../AGENTS.md) § Strategy Probe Candidates.

### The strategy registration seam (`ripdpi-strategy-*`)

The step-kind path above is the proxy-mode surface. A second, **separate**
strategy system feeds TUN-egress mutation (`ripdpi-tunnel-intercept`) and
file-/CLI-driven config — the `ripdpi-strategy-*` crates behind the
`DesyncStrategy` trait:

| Crate | Role |
|-------|------|
| `ripdpi-strategy-trait` | The `DesyncStrategy` contract + the `STRATEGY_FACTORIES` / `STRATEGY_DESCRIPTOR_REGISTRATIONS` `linkme` slices |
| `ripdpi-strategy-{http,ipv6,udp,window,lua}` | Built-in strategy implementations |
| `ripdpi-strategy-config` | YAML/TOML strategy-file model (`StepType`, `LoadedStrategyConfig`) |
| `ripdpi-strategy-registry` | Aggregates the impls into a `StrategyRegistry` and executes the chain |

**To add a factory-backed strategy** (a stateless default — the preferred,
lowest-friction path):

1. Implement `DesyncStrategy` in a `ripdpi-strategy-*` crate (new or existing).
2. Contribute a `StrategyFactory` to `STRATEGY_FACTORIES` with
   `#[linkme::distributed_slice(...)]`. `ripdpi-strategy-window` is the minimal
   worked example.
3. **Central edit:** if the strategy lives in a *new* crate, add
   `extern crate ripdpi_strategy_<name> as _;` to
   `ripdpi-strategy-registry/src/lib.rs` — `linkme` only collects slice entries
   from linked crates. This is the *only* central edit the factory path needs;
   the registry then resolves the stable ID with no match arm.

**Other central edit points** in the registry / config, needed only for the
non-factory paths:

- `BUILTIN_TECHNIQUES` (`ripdpi-strategy-registry`) — a technique with no
  linked factory; pairs with…
- `BuiltinTechnique::plan`'s `match self.definition.id` — the `DesyncAction`
  the built-in technique emits.
- `configured_strategy_from_step` (`ripdpi-strategy-registry`) — a strategy
  that must be built with config parameters (e.g. `UdpLenStrategy::new(delta)`).
- `StepType` + `StepType::registry_id()` (`ripdpi-strategy-config`) — a new
  YAML/TOML step kind. The `StepType` serde representation **is** config
  schema: adding a variant is additive, renaming one (or its `rename`/`alias`)
  is a schema break.

> *Future improvement: `BuiltinTechnique::plan` carries a central
> `match self.definition.id` mapping each built-in technique ID to its
> `DesyncAction`. The `BuiltinTechniqueDefinition` table entries already hold
> id / label / tier / capabilities but **not** the action, so the match cannot
> today be replaced by a table lookup against an existing descriptor field.
> Adding an `action: fn() -> Option<DesyncAction>` field to
> `BuiltinTechniqueDefinition` would make `BUILTIN_TECHNIQUES` the single
> source of truth and collapse `BuiltinTechnique::plan` to a fieldless table
> dispatch, retiring the parallel ID list. It is behavior-neutral only if each
> function pointer reproduces the exact `DesyncAction` the current arm pushes —
> treat it as its own reviewed refactor, not a docs-pass change.*

### Compatibility checks

- Kotlin/Rust wire structs must stay field-order-aligned; `@SerialName` values
  are a wire contract — never rename.
- New proto fields must be defaulted/optional so DataStore round-trips safely.
- Run the desync packet-smoke scenarios — reproduce on-wire behavior before and
  after (`scripts/ci/run-rust-network-e2e.sh`, CLI packet smoke).
- Both proxy mode and VPN/TUN mode apply strategies — verify the new step on
  both paths (fake-TTL semantics differ between TUN and proxy; see the
  `desync-engine` skill).

### Non-root fallback

Required if the step needs raw sockets / `TCP_REPAIR` (`seqovl`,
`multidisorder`, `ipfrag2`-class): the step must degrade to a non-root
approximation or be inert when root is unavailable. `multidisorder` is
**DSL/manual-chain only** today — not a typed UI control.

### Docs / tests / goldens to update

- `docs/native/proxy-engine.md`, `docs/packet-strategy-runtime.md`, and the
  "Current Proxy Strategy Surface" list in `docs/native/README.md`.
- The strategy-probe candidate table in `AGENTS.md` if probeable.
- Rust crate tests in `ripdpi-config` / `ripdpi-desync` / `ripdpi-desync-runtime`;
  add a packet-smoke scenario; `StrategyChains*Test.kt` in `core/data/src/test/`.

### Common mistakes

- Adding the `kind` string to one layer only — it silently no-ops or errors in
  another. Trace config → planner → runtime.
- Reusing a reserved/occupied proto field number (see §5).
- Forgetting fake-TTL behaves differently in TUN vs proxy mode.
- Per-packet `tracing!` on the new step's hot path — ~3 µs/event JNI cost.

---

## 2. Add a relay transport

### Files / crates likely touched

| Side | Location |
|------|----------|
| Proto | `app_settings.proto` — `relay_kind` (field 171, free string) + any `relay_*` parameter fields |
| Kotlin resolver | `core/service/.../services/<Name>RelayKindResolver.kt` + register in `RelayKindResolverRegistry.kt` (see existing `Masque*`, `Naive*`, `ShadowTls*`, `ChainRelay*`, `CloudflareTunnel*` resolvers) |
| Kotlin bridge | `core/engine/.../core/RipDpiRelay.kt`; `RipDpiProxyUIRelayNormalization.kt`, `NativeProxyRelayPreferencesMapper.kt`, `codec/RelaySectionCodec.kt` |
| Rust transport | a crate under `native/rust/crates/` (reuse `ripdpi-vless`/`ripdpi-xhttp`/`ripdpi-tuic`/… or add a new L7 crate) wired into `ripdpi-relay-core` |
| Rust config | `ripdpi-proxy-config` carries the relay runtime config to the Rust boundary |
| Artifact | `libripdpi-relay.so` is built from `crates/ripdpi-relay-android` (a JNI cdylib — **not** linked into `libripdpi.so`) |

### Current extension path

1. `relay_kind` is a **string** enum in proto (current values listed inline in
   `app_settings.proto:248`). Add the new string.
2. Add a transport crate (or extend one) and register it in `ripdpi-relay-core`,
   which is the shared backend/capability surface (`ripdpi-relay-mux` provides
   pooling). `ripdpi-relay-core` rejects unsupported relay/mode combinations
   early — wire the new combination explicitly.
3. Add a `*RelayKindResolver.kt` and register it in `RelayKindResolverRegistry.kt`.
4. If the transport runs as a **subprocess helper** (the NaiveProxy /
   Cloudflare-origin pattern) rather than JNI-embedded, add a `bin` crate and a
   `Subprocess*` supervisor in `:core:service` instead of JNI wiring.
5. If the transport has a URI scheme, extend the subscription/profile importers
   (base64, Clash/Clash.Meta YAML, sing-box JSON, WireGuard-INI).

### The transport-descriptor seam

`ripdpi-relay-core` exposes a `RelayTransportDescriptor` — an additive,
read-only inventory of every concrete relay transport
(`RELAY_TRANSPORT_DESCRIPTORS`, looked up with `relay_transport_descriptor()`,
both re-exported from the crate root). Each row records the static,
`relay_kind`-keyed facts: the stable `relay_kind` string, a label, the SOCKS
capability profile (TCP / UDP / connection reuse), and outbound-bind-IP
support. It is metadata for documentation, diagnostics, and inventory; a crate
test pins it against the runtime source of truth so the two cannot drift.

The descriptor is **not yet wired into runtime dispatch** — relay backend
selection, capability planning, pool sizing, and config parsing still flow
through these decentralized sites:

| Site | What it holds |
|------|---------------|
| `RelayTransportDescriptor` / `RELAY_TRANSPORT_DESCRIPTORS` (`transport_descriptor.rs`) | additive static inventory — `relay_kind`-keyed facts only |
| `RelayKind` enum + `RelayBackendConfig::kind_id()` (`config/`) | the taxonomy and the `relay_kind` → kind-id mapping used by dispatch |
| `RelayKind::supports_finalmask` / `supports_outbound_bind_ip` (`config/kind.rs`) | static capability predicates |
| `planned_backend_capabilities` / `pool_config_for_backend` / `planned_backend_fallback_mode` / `describe_upstream` (`runtime_validation.rs`) | per-kind `match` statements feeding capability, pool sizing, fallback mode |
| `BUILDERS: &[BackendBuilder]` (`backend/builder/builders/mod.rs`) | the `{ supports, build }` dispatch slice |
| `RelayKindResolverRegistry.kt` + per-kind `*RelayKindResolver.kt` (`:core:service`) | the Kotlin-side resolver registry |

Adding a transport is a descriptor row **plus** editing each Rust `match
RelayKind` arm and adding a Kotlin resolver — the `relay_kind` string is still
re-matched at every layer.

*Future improvement — migrate the runtime matches onto the descriptor.* The
four `match RelayKind` statements in `runtime_validation.rs` could become
descriptor lookups, making the table the single source of truth. Two facts are
deliberately **excluded** from the descriptor today because they are not
`relay_kind`-keyed: finalmask support and connection-pool tuning both vary with
VLESS Reality's `xhttp` transport sub-mode (`RelayKind::VlessReality { xhttp }`
splits one `relay_kind` string into two profiles), and `RelayKind::Unsupported`
is a borrowed catch-all with no row. Folding them in needs an `xhttp`-aware key
or a per-row variant. Sequence the migration safest-first: the table already
exists; migrate the `supports_*` predicates; migrate the capability / pool /
fallback matches under the parity test; only then consider exposing the
descriptor for telemetry (a new telemetry field is itself a contract change —
see §6). Keep the `BUILDERS` dispatch slice as-is — it is already a
descriptor-shaped registry.

### Compatibility checks

- `ripdpi-relay-core` config must preserve Cloudflare-tunnel mode, credential
  refs, and Finalmask settings end-to-end — do not drop fields silently.
- New outbound sockets in the transport crate need the `protect()` invariant.
- Subprocess helpers must emit structured readiness/failure events (the
  `RIPDPI-READY` / `RIPDPI-ERROR` pattern) so the supervisor can classify
  failures and redact secrets from surfaced error text.
- The `FleetCompat` golden suite locks RIPDPI against the sibling
  `ripdpi-vpn-deploy` emitter — a relay/routing model change must keep
  `fleet-fixtures.yml` green.

### Non-root fallback

Relay transports are non-root features — no fallback concern, but they must
still function with no relay configured (proxy/VPN modes work standalone).

### Docs / tests / goldens to update

- `docs/native/README.md` § Relay Transport Expansion, `docs/relay-profile-examples.md`.
- Relay crate tests; `*RelayKindResolver` JVM tests; subscription-parser tests
  and the `fleet-fixtures.yml` structural drift gate.
- TLS catalog refresh log if the transport pins TLS templates.

### Common mistakes

- Assuming a new relay needs to be linked into `libripdpi.so` — relay ships as
  `libripdpi-relay.so` from `ripdpi-relay-android`.
- Adding the `relay_kind` string without a resolver — startup silently falls to
  `DefaultRelayKindResolver`.
- Logging the relay server host / credentials (privacy + Data Safety regression).
- Hardcoding a `cargo` dep without `--locked` / `cargo deny` review.

---

## 3. Add a diagnostics probe

### Files / crates likely touched

| Side | Location |
|------|----------|
| Rust contracts | `native/rust/crates/ripdpi-diagnostics-contracts` — `types/` (`scan.rs`, `request.rs`, `observation.rs`) and `wire/` (`request_wire.rs`, `report_wire.rs`, `progress_wire.rs`); `DIAGNOSTICS_ENGINE_SCHEMA_VERSION` in `wire.rs` |
| Rust probe | a probe crate under `ripdpi-diagnostics-*` (e.g. `-http`, `-tls`, `-dns`, `-transport`); aggregated by `ripdpi-diagnostics-runner` |
| Rust engine | `ripdpi-monitor-engine` (`src/contracts.rs`, `src/wire.rs`); lane wiring in `ripdpi-monitor-lane-adapter` |
| Kotlin | `:core:diagnostics`, `:core:diagnostics-data`; bridge `core/engine/.../core/NetworkDiagnostics.kt` |
| Catalog | diagnostics catalog (`:core:data:catalog`, `ripdpi.diagnostics.catalog` plugin) for new packs/profiles |

### Current extension path

- A probe is a task in the diagnostics scan pipeline
  (`ScanRequest` → candidate planning → probe execution → classification →
  report). Add the probe type to `ripdpi-diagnostics-contracts`, implement it in
  the matching `ripdpi-diagnostics-*` crate, register it in
  `ripdpi-diagnostics-runner` / `ripdpi-monitor-lane-adapter`, and surface its
  result through `ripdpi-monitor-engine`.
- Strategy-probe candidates (the 24 TCP / 6 QUIC set) are planned in
  `ripdpi-diagnostics-candidates` — extend the candidate planner there.
- Use the `diagnostics-system` skill — it owns `ScanRequest`/`ScanReport`/
  `ProbeTask` and the catalog pipeline.

### Compatibility checks

- **Bump `DIAGNOSTICS_ENGINE_SCHEMA_VERSION`** when the wire contract changes,
  and keep the Rust and Kotlin sides in lock-step.
- The `DiagnosticsContractGovernanceTest.kt` (`core/diagnostics/src/test/`) and
  `ripdpi-monitor-engine/tests/contract_fixtures.rs` are golden contracts —
  they fail on unexpected payload changes by design.
- Strategy-probe progress payloads (`ScanProgress.strategyProbeProgress`) and
  report payloads (`auditAssessment`, `targetSelection`) are contracts too.
- Automatic probing/audit is unavailable when `Use command line settings` is
  on — do not assume it always runs.

### Non-root fallback

RAW_PATH scans stop the VPN service before probing and connect directly, so
`setsockopt(IP_TTL)` works without `protect()`. A probe that needs raw sockets
must still classify cleanly on non-root devices (degrade, do not crash).

### Docs / tests / goldens to update

- `docs/native/README.md` § Diagnostics and Telemetry; `AGENTS.md` § Current
  Diagnostics Surface / Strategy Probe Candidates.
- Rust probe-crate tests; the contract-fixture goldens (regenerate **only**
  with explicit human bless + rationale); JVM diagnostics orchestration tests.

### Common mistakes

- Changing a wire struct without bumping `DIAGNOSTICS_ENGINE_SCHEMA_VERSION`.
- Blessing `contract_fixtures.rs` to silence a failure instead of investigating.
- Adding a probe that re-classifies the verdict in Kotlin — Rust is
  authoritative for the blocking verdict.
- Forgetting the native scan deadline must finalize partial results before the
  Kotlin stage timeout fires.

---

## 4. Add a policy-memory rule

### Files / crates likely touched

| Side | Location |
|------|----------|
| Kotlin policy | `core/service/.../services/ConnectionPolicyResolver.kt`, `ConnectionPolicySignatureBuilder.kt`, `RememberedConnectionPolicyMatcher.kt`, `ActiveConnectionPolicyStore.kt` |
| Kotlin fingerprint | `NetworkFingerprintProvider.kt`, `NetworkSnapshotFactory.kt`, handover via `NetworkHandoverMonitor.kt` / `NetworkHandoverProcessor.kt` |
| Persistence | `remembered_network_policies` (Room, `:core:data`); `host-autolearn-v2.json`; `HostAutolearnStorage.kt` |
| Rust | `ripdpi-session` (session state + policy store), `ripdpi-runtime-policy`, `ripdpi-runtime-adaptive` |

### Current extension path

- Per-network policy is keyed by a SHA-256 `fingerprintHash` from transport,
  validation state, private DNS mode, DNS servers, and Wi-Fi/cellular identity.
  `remembered_network_policies` stores the exact normalized `proxyConfigJson`,
  optional VPN DNS override, and TCP/QUIC/DNS strategy-family labels.
- Host autolearn is segmented by `networkScopeKey` so networks do not poison
  each other; capacity is bounded (max 512 hosts, known telemetry/system hosts
  filtered out).
- A new rule plugs into `ConnectionPolicyResolver` (the single resolution path
  for startup and live restart) and is replayed on handover.

### Compatibility checks

- **Privacy is non-negotiable.** Hash inputs must stay inside the canonical
  recipe in [`.claude/rules/network-fingerprint-privacy.md`](../../.claude/rules/network-fingerprint-privacy.md):
  never put IMEI/IMSI, raw BSSID/SSID, or device IPs into the hash, logs,
  telemetry, goldens, or any persisted artifact.
- Only the SHA-256 hash + a non-sensitive summary may be persisted.
- A scope-key change orphans every existing remembered policy — treat the
  key recipe as a migration-bearing contract.
- Policy must survive process death: persist on every significant transition,
  not on a timer (LMK / Doze) — see
  [`.claude/rules/android-vpn-lifecycle.md`](../../.claude/rules/android-vpn-lifecycle.md).

### Non-root fallback

Not root-related — but full-matrix audit results stay manual-apply; only
validated recommendations drive remembered-policy persistence.

### Docs / tests / goldens to update

- `docs/native/README.md` § Connection Policy and Network Memory;
  `docs/service-session-scope.md`.
- `ConnectionPolicyResolver` / fingerprint JVM tests; the
  `network-fingerprint-privacy.md` audit grep must stay clean.

### Common mistakes

- Adding a raw identifier to the scope key — privacy + Play Data Safety
  regression, and it collapses or fragments scope keys.
- Single-level scope including DNS servers — a Private DNS toggle orphans the
  policy. (A two-level `network_scope` + `dns_scope` split is described as a
  recommended refactor in the privacy rule — **future improvement**, verify
  current state before relying on it.)
- Saving policy on a periodic timer instead of on state transition.

---

## 5. Add an Android setting that affects Rust runtime behavior

### Files / crates likely touched

| Side | Location |
|------|----------|
| Proto | `core/data/model/src/main/proto/app_settings.proto` — `AppSettings` |
| Kotlin settings | `:core:data:model` / `:core:data:settings` settings models; DataStore mapping |
| Kotlin → native | `core/engine/.../core/RipDpiProxyJsonCodec.kt`, the `core/engine/.../core/codec/*Codec.kt` section codecs, and the `NativeProxy*PreferencesMapper.kt` mappers |
| Rust | `ripdpi-config` / `ripdpi-proxy-config` (config model the JSON deserializes into) → consumed by `ripdpi-proxy-runtime` / `ripdpi-runtime-*` |
| UI | `:app` Compose screens + `strings.xml` (all 7 locales) |

### Current extension path

1. Add a field to `AppSettings`. **Pick the next free field number** — the
   highest in use today is `285`; the `reserved` block at the top of the
   message lists numbers and names that must **never** be reused.
2. All cross-boundary fields must be defaulted/optional or `@Transient` with a
   default (proto3 scalar defaults are implicit; document the "unset" sentinel,
   e.g. `0`/`-1`/`""`, in a trailing comment as the existing fields do).
3. Map the setting through the relevant section codec (`ChainsCodec`,
   `NetworkSectionCodec`, `AdaptiveSectionCodec`, `RelaySectionCodec`,
   `WarpTunnelSectionCodec`, `RuntimeContextCodec`) and `RipDpiProxyJsonCodec`,
   then into the Rust config model.
4. Use the `protobuf-schema-evolution` and `protobuf-datastore` skills.

### Compatibility checks

- Never renumber or reuse a field; reserve removed fields by number **and** name.
- A new UI string must land in all 7 locale files in the same commit;
  `language_name_*` keys stay byte-identical across locales.
- The setting only takes effect after a runtime restart unless it is wired into
  live handover re-resolution — decide and document which.
- If `enable_cmd_settings` (command-line mode) is on, UI-config trials are
  bypassed — verify your setting's interaction with that mode.

### Non-root fallback

If the setting gates a root-only capability, it must be inert (not an error)
when `root_mode_enabled` is off or root is unavailable.

### Docs / tests / goldens to update

- `docs/design-system.md` / feature docs as relevant; `docs/feature-test-checklist.md`.
- `protobuf` round-trip tests; DataStore migration tests; the
  `core/engine` codec/mapper tests; golden config-translation fixtures.

### Common mistakes

- Reusing a `reserved` field number — silent wire corruption.
- Adding a non-defaulted cross-boundary field — DataStore round-trip breaks.
- Adding a UI string in `values/` only — `MissingTranslation` fails CI.
- Letting Rust re-derive the setting instead of consuming the Kotlin-produced
  JSON (config-contract direction violation).

---

## 6. Add a telemetry event

### Files / crates likely touched

| Side | Location |
|------|----------|
| Rust | `native/rust/crates/ripdpi-telemetry` (`src/lib.rs`, `src/recorder/` — `snapshot.rs`, `state.rs`, `registration.rs`, counters/histograms) |
| Rust emitters | the runtime crate that observes the event (`ripdpi-proxy-runtime`, `ripdpi-tunnel-core`/`ripdpi-tunnel-android`, `ripdpi-monitor-engine`) |
| Kotlin consumer | `:core:service` telemetry coordinators — `ProxyTelemetryCoordinator.kt`, `VpnTelemetryCoordinator.kt`, `VpnTelemetrySnapshot.kt`, `ServiceTelemetryLoopCoordinator.kt` |

### Current extension path

- Telemetry is **pull-model**: `:core:service` polls native snapshots once per
  second and stores only metadata. Add the field to the telemetry snapshot
  struct in `ripdpi-telemetry`, populate it in the emitting runtime, and read
  it in the matching Kotlin coordinator.
- For a discrete event (not a counter), use the **bounded event ring** — it is
  size-capped on purpose; do not make it unbounded.
- Use the `rust-android-telemetry` skill for channel selection.

### Compatibility checks

- Telemetry payloads are **golden contracts** — Rust goldens under each crate's
  `tests/golden/`, JVM goldens under `src/test/resources/golden/`. Default test
  mode is read-only and fails on unexpected change.
- Volatile fields (timestamps, generated ids, ephemeral ports) are scrubbed
  before comparison; semantic fields stay strict. If a new field is volatile,
  add it to `tests/golden/scrub.json` — do not bless around it.
- Deterministic JSON serialization (stable field order) is required for goldens.
- Control-plane vs data-plane channel: control-plane uses `android_logger`;
  the data plane stays on `tracing` and **off** per-packet paths.

### Non-root fallback

Not root-related.

### Docs / tests / goldens to update

- `docs/logging-conventions.md`; `docs/native/README.md` § Diagnostics and
  Telemetry (the polled-metadata list).
- Rust + JVM telemetry/logging goldens — refresh together with
  `scripts/tests/bless-telemetry-goldens.sh` **only** under human supervision.

### Common mistakes

- Emitting a telemetry event on a per-packet / per-byte path (~3 µs/event JNI
  cost — a measurable CPU bottleneck at 1 Gbps).
- Logging raw BSSID/IMEI/IMSI/SSID or device IPs — privacy / Data Safety
  regression. Use the SHA-256 scope hash only.
- Blessing telemetry goldens to "fix" a diff instead of extending `scrub.json`
  for a genuinely volatile field.
- An unbounded event buffer — leaks memory across a long session.

---

## 7. Add a root-helper privileged operation

### Files / crates likely touched

| Side | Location |
|------|----------|
| Protocol | `native/rust/crates/ripdpi-root-helper-protocol` — `commands.rs` (the `CMD_*` string constants), `params.rs`, `wire.rs`, `scm_rights.rs` |
| Helper binary | `native/rust/crates/ripdpi-root-helper` — `src/dispatch.rs` + `dispatch/`, `src/handlers.rs` + `handlers/`, `src/main.rs` |
| Privileged impl | `native/rust/crates/ripdpi-privileged-ops` — the raw-socket / `TCP_REPAIR` / fragmentation primitives |
| Runtime dispatch | `native/rust/crates/ripdpi-runtime-platform` — `root_helper.rs`, `root_helper_client.rs`, `fake_send/root_helper_dispatch.rs`, and the relevant op module (`raw_packet.rs`, `ip_fragmentation/`, …) |
| Kotlin lifecycle | `core/service/.../services/RootHelperManager.kt` (extract/start/stop), `RootDetector.kt` (root test) |

### Current extension path

1. Add a `CMD_<NAME>` string constant in
   `ripdpi-root-helper-protocol/src/commands.rs` and document the request/
   response JSON shape there (the existing constants document theirs inline).
   Add parameter/wire types in `params.rs` / `wire.rs`.
2. Implement the handler in `ripdpi-root-helper` (`dispatch.rs` routes the
   command string to a `handlers/` function); implement the actual privileged
   primitive in `ripdpi-privileged-ops`.
3. In `ripdpi-runtime-platform`, the dispatch function must check
   `with_root_helper()` first and **fall back to a local non-privileged path**
   when no helper is registered. Replacement fds from `TCP_REPAIR`-class ops are
   swapped via `dup2()`.
4. The IPC carries fds via `SCM_RIGHTS` — use `scm_rights.rs`.

> Precedent: `CMD_SEND_SYN_HIDE_TCP`, `CMD_SEND_ICMP_WRAPPED_UDP`, and
> `CMD_RECV_ICMP_WRAPPED_UDP` already exist in `commands.rs` but are **not**
> wired through `DesyncMode`, protobuf settings, or UI (Tier-3 primitives).
> Wiring a primitive end-to-end is itself a feature task — schema, UI,
> packet-smoke, and security review — see `architecture/README.md`.

### Compatibility checks

- The root-helper IPC protocol is a wire contract — `commands.rs` / `wire.rs`
  changes need golden coverage and lock-step helper + client updates.
- New raw sockets still obey the `protect()` invariant for non-loopback targets.
- The helper runs as uid 0 — treat every input as untrusted; this is
  security-sensitive code (route review through `security-reviewer` /
  `unsafe-code-auditor`).

### Non-root fallback — **mandatory**

This is the defining constraint. Every privileged op **must** have a
non-privileged path or degrade to inert: `ripdpi-runtime-platform` dispatch
checks `with_root_helper()` and falls back to local Linux calls. The app must
fully function on non-rooted devices; the new op is opt-in behind
`root_mode_enabled`. Tactics are tiered `non_root_production` /
`rooted_production` / `lab_diagnostics_only` — device capability checks decide
whether an emitter runs, they do not change the taxonomy.

### Docs / tests / goldens to update

- `AGENTS.md` § Root Helper IPC; `docs/native/README.md` (root-helper command
  list); `docs/packet-strategy-runtime.md`.
- `ripdpi-root-helper` / `ripdpi-privileged-ops` crate tests; protocol golden
  fixtures; a CI process-death / capability-probe scenario.

### Common mistakes

- Shipping a privileged op with no non-root fallback — breaks the non-rooted
  baseline.
- Forgetting `probe_capabilities` must advertise the new capability so the
  runtime knows whether to attempt it.
- Adding the `CMD_*` constant without the dispatch arm, or vice versa.
- Treating helper input as trusted — it is a uid-0 process boundary.

---

## Cross-references

| Topic | Source |
|-------|--------|
| Domain term definitions | [`GLOSSARY.md`](GLOSSARY.md) |
| Module map, control flow, config flow | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Crate taxonomy + dependency direction | [`NATIVE_RUST.md`](NATIVE_RUST.md) |
| Desync chains, `TcpChainStep`, fake-TTL | `desync-engine` skill |
| Scan pipeline, `ScanRequest`, probe families | `diagnostics-system` skill |
| Telemetry channels, bounded ring, goldens | `rust-android-telemetry` skill |
| Proto field discipline, DataStore round-trip | `protobuf-schema-evolution`, `protobuf-datastore` skills |
| JNI export safety | `rust-android-jni` skill |
| Golden bless discipline | [`.claude/rules/golden-bless-discipline.md`](../../.claude/rules/golden-bless-discipline.md) |
| Socket-protect invariant | [`.claude/rules/vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md) |
| Network-fingerprint privacy | [`.claude/rules/network-fingerprint-privacy.md`](../../.claude/rules/network-fingerprint-privacy.md) |
| VPN lifecycle / process-death persistence | [`.claude/rules/android-vpn-lifecycle.md`](../../.claude/rules/android-vpn-lifecycle.md) |
| Toolchain pin / `--locked` discipline | [`.claude/rules/rust-toolchain-pin.md`](../../.claude/rules/rust-toolchain-pin.md) |
| Project rules, CI jobs, skills index | [`AGENTS.md`](../../AGENTS.md) |
