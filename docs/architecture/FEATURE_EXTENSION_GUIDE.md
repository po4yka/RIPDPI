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
  `core/service/src/main/res/values/strings.xml` must land in all nine
  translated locale files (`values-{ru,es,de,fr,fa,ar,zh-rCN,hi,pt-rBR}`) in the same
  commit (`lint.xml` sets `MissingTranslation` to `error`).
- **`VpnService.protect()` invariant.** Any new non-loopback `TcpStream`/
  `UdpSocket`/`mio` socket in Rust must be protected before `connect`/`bind`.
  See [`.claude/rules/vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md).
- **No backend server.** Features work offline/locally; external data is static
  files or bundled assets only.
- **Non-rooted baseline.** The app must fully function without root; root-only
  capability is opt-in behind `root_mode_enabled` and degrades gracefully.
- **JNI containment.** Production normal/build dependencies may touch `jni` or
  `android-support` only in the 13 allowlisted L8 Android crates (12 currently
  depend on `jni`; see [`NATIVE_RUST.md`](NATIVE_RUST.md) §5). Dev-only test
  edges are permitted by the architecture gate. New core logic stays JNI-free.
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

- **A new TCP chain step kind** (`split`, `syndata`, `seqovl`, `fakerst`, … — 15 today)
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
  (see §3) — the current candidate families and builder locations are listed in
  [`AGENTS.md`](../../AGENTS.md) § Strategy Probe Candidates.

### The strategy registration seam (`ripdpi-strategy-*`)

The step-kind path above is the proxy-mode surface. A second, **separate**
strategy system feeds TUN-egress mutation (`ripdpi-tunnel-intercept`) and
file-/CLI-driven config — the `ripdpi-strategy-*` crates behind the
`DesyncStrategy` trait:

| Crate | Role |
|-------|------|
| `ripdpi-strategy-trait` | The `DesyncStrategy` contract + the `STRATEGY_STEP_REGISTRATIONS` / `STRATEGY_DESCRIPTOR_REGISTRATIONS` `linkme` slices, the `StrategyStepDescriptor` / `StrategyStepFactory` platform types |
| `ripdpi-strategy-core` | The stateless core techniques (`split`, `disorder`, `fake`, `oob`, `fake_rst`, `seq_overlap`, `ip_frag`, `multi_disorder`, `tls_rec`, `tls_rand_rec`) + descriptor-only `synack` / `synack_split` registry placeholders. The TUN ingress SYN-ACK interceptor consumes those YAML step ids separately. |
| `ripdpi-strategy-{http,ipv6,udp,window,lua}` | Built-in strategy implementations |
| `ripdpi-strategy-config` | YAML/TOML strategy-file model (`StepType`, `LoadedStrategyConfig`) |
| `ripdpi-strategy-registry` | Aggregates the impls into a `StrategyRegistry` and executes the chain |

Strategy resolution is a **descriptor/factory platform**: every strategy step
is one `StrategyStepRegistration` in the `STRATEGY_STEP_REGISTRATIONS` `linkme`
slice — a `StrategyStepDescriptor` (id, label, accepted aliases, required tier
and capabilities, parameter metadata) paired with the `StrategyStepFactory`
that builds it. There is no `BUILTIN_TECHNIQUES` table and no central `match`
over step ids.

**To add a strategy:**

1. Implement `DesyncStrategy` in a `ripdpi-strategy-*` crate (new or existing —
   the stateless core techniques live in `ripdpi-strategy-core`).
2. Contribute one `StrategyStepRegistration` to `STRATEGY_STEP_REGISTRATIONS`
   with `#[linkme::distributed_slice(...)]`, choosing the `StrategyStepFactory`
   variant: `Stateless` for a zero-argument default (`ripdpi-strategy-window`
   is the minimal worked example, `ripdpi-strategy-core` the macro-driven one),
   `Configured` for a step built from parsed parameters (`udplen`, `ipv6_ext`),
   or `Unimplemented` for a descriptor-only registry placeholder (`synack` / `synack_split`; TUN ingress handles those ids separately).
3. **Central edit:** if the strategy lives in a *new* crate, add
   `extern crate ripdpi_strategy_<name> as _;` to
   `ripdpi-strategy-registry/src/lib.rs` — `linkme` only collects slice entries
   from linked crates. This is the *only* central edit the registration path
   needs; the registry then resolves the descriptor with no match arm.

For a new **YAML/TOML step kind**, also add a `StepType` variant in
`ripdpi-strategy-config` and its spellings to `StepType::from_wire` /
`StepType::registry_id`. `StepType` is string-backed (known/unknown): an
unrecognized `type:` value parses to `StepType::Unknown` and fails at registry
resolution rather than at serde decoding. Adding a variant is additive;
renaming an id or alias is a schema break. The `descriptor_drift` tests pin the
config parser, the descriptors, and registry resolution against each other.

**Lua is the one special case.** `ripdpi-strategy-lua` is feature-gated
(`lua-strategies`), so the `lua` step is resolved directly by the registry —
not through the slice — and `LUA_STEP_DESCRIPTOR` is registry-owned.

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

1. `relay_kind` is a **string** enum in proto (current values are documented next to the field in `app_settings.proto`). Add the new string.
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

`ripdpi-relay-core` keeps one private `RelayTransportRegistration` row per
supported kind in `RELAY_TRANSPORT_REGISTRATIONS`. Each row combines the public
`RelayTransportDescriptor`, an optional backend builder, and the fallback mode;
`relay_transport_descriptor()` is the public lookup. The descriptor records the
stable kind string, label, SOCKS capability profile (TCP / UDP / connection
reuse), and outbound-bind-IP support.

`runtime_validation` resolves the **generic** capability decisions through the
descriptor: `planned_backend_capabilities` reads TCP / UDP / reuse from it, and
the outbound-bind-IP validation gate reads `supports_outbound_bind_ip`. The
remaining per-kind logic still flows through these decentralized sites:

| Site | What it holds |
|------|---------------|
| `RelayTransportRegistration` / `RELAY_TRANSPORT_REGISTRATIONS` (`transport_descriptor.rs`) | descriptor, optional builder, and fallback mode for each supported kind |
| `RelayKind` enum + `RelayBackendConfig::kind_id()` (`config/`) | the taxonomy and the `relay_kind` → kind-id mapping used by dispatch |
| `RelayKind::supports_finalmask` (`config/kind.rs`) | the sub-mode-dependent finalmask predicate (varies with VLESS `xhttp`) |
| `pool_config_for_backend` / `describe_upstream` (`runtime_validation.rs`) | remaining per-kind pool sizing and upstream description |
| `RelayKindResolverRegistry.kt` + per-kind `*RelayKindResolver.kt` (`:core:service`) | the Kotlin-side resolver registry |

Adding a transport starts with one registration row, then updates the remaining
Rust `match RelayKind` arms and adds a Kotlin resolver.

*Future improvement — migrate the remaining runtime matches onto the
descriptor.* `planned_backend_capabilities` and the outbound-bind-IP gate are
already descriptor lookups; the
`relay_planned_capabilities_are_pinned_for_every_kind` and
`relay_transport_registry_is_consistent` tests pin the registry
against every `RelayKind`. The remaining `match RelayKind` statements in
`runtime_validation.rs` — `pool_config_for_backend`,
`describe_upstream` — and
`RelayKind::supports_finalmask` stay match-based because they are not purely
`relay_kind`-keyed: pool tuning and finalmask support both vary with VLESS
Reality's `xhttp` transport sub-mode (`RelayKind::VlessReality { xhttp }`
splits one `relay_kind` string into two profiles), the fallback mode and
upstream description are backend-specific, and `RelayKind::Unsupported` is a
borrowed catch-all with no row. Folding them in needs an `xhttp`-aware key or a
per-row variant.

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
- Strategy-probe candidates are planned in `ripdpi-diagnostics-candidates` —
  extend the candidate planner there and keep capability gates explicit.
- Use the `diagnostics-system` skill — it owns `ScanRequest`/`ScanReport`/
  `ProbeTask` and the catalog pipeline.

### The probe registration seam

Diagnostics uses explicit static registries rather than link-time discovery.
Edit the seam required by the probe:

| Seam | Where | Edit it for |
|------|-------|-------------|
| Scan stage runner | `PROBE_STAGE_REGISTRATIONS` in `ripdpi-monitor-engine/src/engine/runners/registry.rs` | a new connectivity stage the engine schedules |
| Lane adapter | the `LANE_ADAPTERS` table + an `adapters` module in `ripdpi-monitor-lane-adapter` | surfacing a new `ripdpi-diagnostics-*` crate into the engine |
| Concrete probe | a `Probe` impl, scheduled inventory row, and `ProbeDescriptor` in `ripdpi-diagnostics-probes` | a single named offline/online check |
| Strategy candidate | a `StrategyCandidateSpec` planned by `build_strategy_probe_suite()` in `ripdpi-diagnostics-candidates` | a new strategy configuration in the TCP/QUIC matrix |

`StrategyCandidateSpec` is the candidate descriptor pattern — id, family,
capability requirements (`requires_fake_ttl`, `requires_capabilities`), and
eligibility. Connectivity probes use `PROBE_DESCRIPTORS` plus matching
`ProbeStageRegistration` rows. A lane adapter is needed only when introducing
a new crate seam. The full registration flow lives in
[`DIAGNOSTICS_ARCHITECTURE.md`](DIAGNOSTICS_ARCHITECTURE.md).

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
| UI | `:app` Compose screens + localized resources (all 9 locales) |

### Current extension path

1. Add a field to `AppSettings`. **Pick the next free field number** — the
   highest in use today is `410`; the `reserved` block at the top of the
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
- A new UI string must land in the default file and all eight translations in the same commit;
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
- Use the `rust-observability` skill for channel selection and redaction.

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

## 8. The cross-layer feature-contract harness

Any feature that lands in one of the five descriptor / section platforms
covered by this guide — proxy setting (§5), relay transport (§2), strategy
step (§1), diagnostics probe (§3), root-helper command (§7) — should also
add or update a manifest in
[`native/rust/crates/feature-contract-harness/manifests/`](../../native/rust/crates/feature-contract-harness/manifests/).

The harness is a thin test layer over JSON manifests. Each manifest declares
the cross-layer surface for one feature (proto field, settings section
mapper, wire DTO, Rust descriptor / registration, …) and pins a stable
marker substring per layer. When a contributor edits one layer and forgets
the others, the harness fails with the file path, the missing marker, the
per-layer fix hint, and the full shotgun-surgery checklist.

### When to add or update a manifest

| Trigger | Action |
|---------|--------|
| Adding a new feature to any of the 5 families | Add a new `manifests/<family>/<name>.json`. |
| Intentionally renaming a marker or path | Update the manifest in the same commit as the rename. |
| Adding a 6th cross-layer platform | Extend `KNOWN_FAMILIES` in `feature-contract-harness/src/lib.rs`, add a new family test under `tests/`, and add at least one manifest. |

The manifest schema and authoring workflow live in the crate's
[`README.md`](../../native/rust/crates/feature-contract-harness/README.md).

### What it does NOT replace

- Existing in-crate drift tests (`descriptor_drift.rs`, `command_descriptor.rs`
  tests, `RelayKindDescriptorDriftTest`) — those pin platform-internal
  invariants. The harness pins the cross-layer touchpoints.
- The wire-format goldens for native config JSON, telemetry events, root-helper
  protocol — those are governed by
  [`.claude/rules/golden-bless-discipline.md`](../../.claude/rules/golden-bless-discipline.md).

### Running

```sh
cargo test --locked --manifest-path native/rust/Cargo.toml -p feature-contract-harness
./gradlew :core:data:model:test --tests 'ProxySettingFeatureContractTest'
./gradlew :core:service:test --tests 'RelayKindFeatureContractTest'
```

The Rust side covers all 5 families. The Kotlin side covers the two families
with Kotlin surface (proxy setting + relay kind) and reads the same manifest
tree, so a single edit to a manifest propagates to both languages.

---

## Cross-references

| Topic | Source |
|-------|--------|
| Domain term definitions | [`GLOSSARY.md`](GLOSSARY.md) |
| Module map, control flow, config flow | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Crate taxonomy + dependency direction | [`NATIVE_RUST.md`](NATIVE_RUST.md) |
| Desync chains, `TcpChainStep`, fake-TTL | `desync-engine` skill |
| Scan pipeline, `ScanRequest`, probe families | `diagnostics-system` skill |
| Telemetry channels, bounded ring, goldens | `rust-observability` skill |
| Proto field discipline, DataStore round-trip | `protobuf-schema-evolution`, `protobuf-datastore` skills |
| JNI export safety | `rust-jni` skill |
| Golden bless discipline | [`.claude/rules/golden-bless-discipline.md`](../../.claude/rules/golden-bless-discipline.md) |
| Socket-protect invariant | [`.claude/rules/vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md) |
| Network-fingerprint privacy | [`.claude/rules/network-fingerprint-privacy.md`](../../.claude/rules/network-fingerprint-privacy.md) |
| VPN lifecycle / process-death persistence | [`.claude/rules/android-vpn-lifecycle.md`](../../.claude/rules/android-vpn-lifecycle.md) |
| Toolchain pin / `--locked` discipline | [`.claude/rules/rust-toolchain-pin.md`](../../.claude/rules/rust-toolchain-pin.md) |
| Project rules, CI jobs, skills index | [`AGENTS.md`](../../AGENTS.md) |
