# Configuration Contracts

How a user setting travels from the protobuf schema, through Kotlin mappers and
the native JSON codec, into the Rust runtime config — and the **contract rules**
that keep every hop current-only, fail-closed, and additive-field tolerant.

Companion docs: [`ARCHITECTURE.md`](ARCHITECTURE.md) §6 (config flow overview),
[`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) §5 (adding a setting),
[`JNI_CONTRACT.md`](JNI_CONTRACT.md) (the boundary the JSON crosses).

This document is **descriptive** — it changes no protobuf or JSON behavior. It
cites the exact files that own each contract.

---

## The pipeline and who owns each hop

```
AppSettings protobuf  ─┐
  core/data/model/src/main/proto/app_settings.proto
                       ▼
Kotlin settings + strategy models
  core/data/model/.../StrategyChain{Protobuf,Model,Parser,Validation,Dsl}.kt
  core/data/settings/.../DefaultStrategyChains.kt
                       ▼
Native JSON codec  (Kotlin authoritative)
  core/engine/.../core/RipDpiProxyJsonCodec.kt
  core/engine/.../core/codec/{Adaptive,Chains,FakePacket,Network,Relay,RuntimeContext,WarpTunnel}SectionCodec.kt
  core/engine/.../core/NativeProxy{Desync,Quic,Relay,Runtime,Warp}PreferencesMapper.kt
                       ▼   native config JSON (string over JNI)
Rust deserialization → RuntimeConfig
  native/rust/crates/ripdpi-proxy-config  (src/convert/, src/types/, src/presets/)
  native/rust/crates/ripdpi-config        (src/model/, src/model/defaults.rs)
  native/rust/crates/ripdpi-tunnel-config
```

| Contract | Owner file(s) |
|----------|---------------|
| Protobuf settings schema | `core/data/model/src/main/proto/app_settings.proto`, `geosite.proto` |
| Protobuf ↔ Kotlin model | `core/data/model/src/main/kotlin/com/poyka/ripdpi/data/StrategyChain{Protobuf,Model,Parser,Validation,Dsl}.kt`; defaults in `core/data/settings/.../DefaultStrategyChains.kt` |
| Native config JSON | `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxyJsonCodec.kt` + the seven `core/engine/.../core/codec/*SectionCodec.kt` |
| Settings → native mappers | `core/engine/.../core/NativeProxy{Desync,Quic,Relay,Runtime,Warp}PreferencesMapper.kt` |
| Rust runtime config | `native/rust/crates/ripdpi-proxy-config`, `native/rust/crates/ripdpi-config` |
| Tunnel config | `native/rust/crates/ripdpi-tunnel-config` |
| Strategy-pack config | `native/rust/crates/ripdpi-strategy-config/src/lib.rs` |
| Diagnostics wire contract | `native/rust/crates/ripdpi-diagnostics-contracts/src/wire.rs` |
| Root-helper IPC protocol | `native/rust/crates/ripdpi-root-helper-protocol/src/commands.rs` |
| Telemetry payloads | `native/rust/crates/ripdpi-telemetry`, event ring `native/rust/crates/android-support/src/events.rs` |
| Support settings deep-link packages | `core/data/settings/src/main/kotlin/com/poyka/ripdpi/data/support/*`, [`docs/support-settings-deep-links.md`](../support-settings-deep-links.md) |

> **Direction of authority.** Kotlin is authoritative for user-facing models,
> defaults, validation, and JSON serialization. Rust **consumes** the JSON and
> must never re-derive a user setting.

---

## 1. Protobuf field compatibility rules

Owner: `core/data/model/src/main/proto/app_settings.proto` (proto3,
`java_package = "com.poyka.ripdpi.proto"`, `java_multiple_files = true`).
The settings store is Jetpack DataStore — a wire-format change is a
**persisted-data** change.

- **Never reuse a field number.** Once assigned, a number is permanent. The
  highest `AppSettings` number in use today is `410`
  (`simple_failover_awg_profile_id`). Determine the next unreserved number from
  the current proto rather than relying on this prose snapshot.
- **Never reuse a field name.** A name carries semantics into goldens and DSL.
- **On removal, reserve both.** `AppSettings` already does this — `reserved 15,
  16, 17, 29, 30, 31, 32, 69, 71, 93, 94, 130, 190;` plus the matching
  `reserved "desync_method", … "settings_migration_level",
  "relay_masque_cloudflare_mode";`. Add the removed number **and** name to
  those `reserved` lists in the same commit.
- **Safe defaults.** proto3 scalar defaults are implicit (`0` / `""` / `false`
  / empty `repeated`). A field whose `0`/empty value is not a safe "unset" must
  document its sentinel in a trailing comment — the existing schema does this
  (`NumericRange` fields use `-1 means unset`; many `int32` fields note
  `0 = disabled`). Pick a sentinel whose default value is the inert behavior.
- **Nested messages have their own numbering.** `StrategyTcpStep` (numbers up
  to 17) and `StrategyUdpStep` (up to 5) are independent — a new per-step
  parameter takes the next number *inside that message*.
- **`message`/`enum` strings vs proto fields.** Enum-like settings (`relay_kind`,
  `tls_fake_profile`, …) are stored as `string`, not proto `enum`. Adding a
  value is a string-set change, not a schema change — but the string is still
  a frozen identifier (see §5).
- A `string` field that historically held a migration counter,
  `settings_migration_level` (number 130), is **reserved/removed** — settings
  migration is handled by DataStore migrations now, not an in-message counter.
- Retired relay xHTTP tag numbers 215 and 216 remain reserved. There is no
  raw-wire semantic migration from historical tags 214–216 to the current
  fields 258–260: tag 214 is the current `strategy_chain_yaml` field, and
  persisted bytes are decoded strictly according to the current protobuf.
  Standard protobuf unknown-field preservation remains enabled.

Use the `protobuf-schema-evolution` and `protobuf-datastore` skills.

---

## 2. Native JSON compatibility rules

Owner: `RipDpiProxyJsonCodec.kt` (an `internal object`) plus the seven section
codecs. The JSON string is produced by Kotlin `kotlinx.serialization` and
consumed by Rust `serde` — the two serializers must agree on every key.

- **Discriminated union.** The payload is a sealed type tagged by `"kind"`
  (`Json { classDiscriminator = "kind" }`). Two variants:
  `@SerialName("command_line")` and `@SerialName("ui")`. The Rust mirror is
  `#[serde(tag = "kind", rename_all = "snake_case", rename_all_fields = "camelCase")]`.
  The two `kind` values are **frozen**.
- **Field naming is `camelCase`** on both sides (Kotlin default + Rust
  `#[serde(rename_all = "camelCase")]`). A JSON key is a wire contract — never
  rename it. Renaming `@SerialName`/struct fields breaks decode of both live
  config and persisted remembered policies.
- **The `ui` payload is grouped, not flat.** Sections: `listen`, `protocols`,
  `chains`, `fakePackets`, `parserEvasions`, `adaptiveFallback`, `quic`,
  `hosts`, `upstreamRelay`, `warp`, `hostAutolearn`, `wsTunnel`. The legacy
  *flat* UI shape is **explicitly rejected** — `RipDpiProxyJsonCodec` keeps a
  `legacyFlatUiKeys` set and `validateUiPayloadShape` throws if any appears.
- **`encodeDefaults = true`.** Kotlin writes every field every time, so Rust
  may rely on the field being present — but fields tagged
  `@EncodeDefault(EncodeDefault.Mode.NEVER)` (`nativeLogLevel`,
  `rootHelperSocketPath`, `geoipDbPath`, `geositeDbPath`) are omitted when
  null, so the Rust side **must** default them.
- **Additive and defaulted, both sides.** A new JSON key must have a default in
  the Kotlin codec model *and* a `#[serde(default)]` on the Rust struct. The
  Rust proxy/tunnel structs are **not** `#[serde(deny_unknown_fields)]`, so a
  current-schema consumer silently ignores an additive key it does not know.
- `environmentKind` is carried as the `EnvironmentKind` enum **variant name**
  string (`"Field"` / `"Emulator"` / `"Unknown"`); Rust parses it back into
  `ripdpi_config::EnvironmentKind`, defaulting unknown to `Unknown`.
- The config-translation JSON is covered by golden tests — treat a golden diff
  as a wire-contract change ([`.claude/rules/golden-bless-discipline.md`](../../.claude/rules/golden-bless-discipline.md)).

### System HTTP proxy service mode — listener fields

The "System HTTP proxy service mode" feature adds two `listen`-section keys and
a set of `AppSettings` proto fields. All are additive (Kotlin default + Rust
`#[serde(default)]`) within the current schema.

- **`listen.mixed`** (`bool`, default `false`) → `RuntimeConfig.network.mixed`.
  Turns the single local listener into a *mixed* inbound that serves SOCKS5,
  SOCKS4 **and** HTTP CONNECT, dispatched by peeking the first request byte
  (`0x05`→SOCKS5, `0x04`→SOCKS4, `'C'`→HTTP CONNECT). Mode precedence in the
  adapter is `transparent > mixed > http_connect > byte_prefixed`. When mixed is
  on and the user has not set an explicit port, the Kotlin mapper
  (`buildListenConfig`) defaults the port to **2080** (mirroring the reference
  `mixedPort`), leaving the plain-SOCKS **1080** default and its goldens intact.
- **`listen.authToken`** (`String?`, default `null`) → listener auth token. The
  native `apply_listen_section` **rejects any non-loopback `listen.ip` without a
  non-empty `authToken`** — this guard is load-bearing and must not be removed.
- **Allow-LAN** binds the listener to `0.0.0.0`. The UI toggle auto-generates a
  128-bit hex access token and sends it as `listen.authToken`; `buildListenConfig`
  only emits `0.0.0.0` when a token is present and otherwise **degrades to
  loopback**. The token is a credential: never logged and **never written to
  settings backups** (full or share), so a restored allow-LAN session falls back
  to loopback until re-enabled.

`AppSettings` proto fields: `mixed_inbound_enabled = 403`, `proxy_allow_lan = 404`,
`proxy_lan_auth_token = 405` (never exported), `append_http_proxy = 406`.
`append_http_proxy` is VPN-mode-only: on Android Q+ the `VpnService.Builder`
calls `setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", <effective port>,
["localhost","127.0.0.1","::1"]))`. The advertised port is the *effective
listener port* (`effectiveListenerPort`, identical to `buildListenConfig`).

---

## 3. Rust config deserialization / defaulting rules

Owner: `ripdpi-proxy-config` (entry points `parse_proxy_config_json`,
`runtime_config_from_ui`, `runtime_config_from_command_line`,
`runtime_config_from_payload`), `ripdpi-config` (`src/model/`,
`src/model/defaults.rs`), `ripdpi-tunnel-config`.

- **Every optional field is defaulted.** The config crates carry ~235
  `#[serde(default)]` attributes. A new optional field **must** be
  `#[serde(default)]` or `Option<T>`; the required current `schemaVersion`
  envelope is the deliberate exception and must fail deserialization when
  absent.
- **Custom defaults are named functions.** `#[serde(default = "fn")]` — e.g.
  `default_relay_server_port`, `default_tcp_chain_steps`,
  `default_seqovl_fake_mode`, `default_ipv6_extension_profile`,
  `default_fake_payload_profile`, `default_true`. The default must reproduce
  the inert current behavior when a current-version producer omits that field.
- **Unknown fields are tolerated** for proxy / tunnel / diagnostics config — no
  `#[serde(deny_unknown_fields)]`. The single exception is
  `ripdpi-strategy-config` (`src/lib.rs`, `#![forbid(unsafe_code)]`), which
  uses `deny_unknown_fields` for strict strategy-pack YAML/TOML parsing — a
  typo there is an error, by design.
- **Do not rename wire fields.** Current-only schema enforcement does not make
  silent key renames safe; a breaking rename requires a coordinated version
  bump and producer/consumer update.
- **The normalized shape is `RuntimeConfig`**, wrapped by `RuntimeConfigEnvelope
  { config, runtime_context, log_context, native_log_level }`
  (`ripdpi-proxy-config/src/types/payload.rs`).
- **Strategy string → enum parsing must reject unknown identifiers.** The `parse_*` helpers
  (`parse_desync_mode`, `parse_tcp_chain_step_kind`, `parse_tls_fake_profile`,
  `parse_quic_fake_profile`, `parse_quic_initial_mode`, `parse_http_fake_profile`,
  `parse_udp_chain_step_kind`, `parse_udp_fake_profile`) decode the stable
  identifier strings from §5; an unrecognized executable strategy identifier
  must reject the config rather than silently change strategy semantics.

---

## 4. Exact config replay rules

Per-network winners are persisted and replayed verbatim — see
[`docs/native/README.md`](../native/README.md) § Connection Policy and Network
Memory.

- **What is persisted:** `remembered_network_policies` stores the exact
  normalized current-schema `proxyConfigJson` (plus an optional VPN DNS
  override and the TCP/QUIC/DNS strategy-family labels) for a validated network
  winner. On reconnect that JSON is replayed.
- **The strategy body is the identity; the context is not.** Before persistence
  the volatile context is stripped — `RipDpiProxyJsonCodec.stripRuntimeContext`
  removes `runtimeContext` and `logContext`. On replay,
  `RipDpiProxyJsonCodec.rewriteJson` patches the stored JSON tree and re-applies
  the *current* `hostAutolearnStorePath`, `networkScopeKey`, `runtimeContext`,
  `logContext`, `rootMode`, geo-DB paths, and session overrides, then
  re-encodes. Session overrides (local listen-port override, auth token) are
  merged fresh via `SessionOverrideCodec.merge` — they are never part of the
  persisted identity.
- **Replay invariant:** a stored current-schema `proxyConfigJson` must preserve
  the same strategy semantics on the current build. This is why §2's
  no-rename rule is absolute — a renamed key silently drops persisted strategy
  state for every remembered network.
- **Retired replay fails closed:** missing or non-current proxy schemas and
  otherwise invalid remembered payloads are not migrated. The match records a
  policy failure, participates in suppression accounting, and falls back to
  the baseline configuration instead of aborting startup.
- `networkScopeKey` segments host autolearn (`host-autolearn-v2.json`); it is a
  replay *input*, re-applied by `rewriteJson`, not a stored identity field.
- Full-matrix audit results are manual-apply; only validated recommendations
  drive remembered-policy persistence.

---

## 5. Stable identifiers

Every cross-boundary identifier string is a frozen wire contract. **Add new
values; never rename or repurpose an existing one.**

| Identifier class | Values / source of truth | Consumers |
|------------------|--------------------------|-----------|
| **Relay kind** | `relay_kind` (proto field 171): `off`, `vless`, `vless_reality`, `hysteria2`, `chain_relay`, `masque`, `anytls`, `cloudflare_tunnel`, `tuic_v5`, `shadowtls_v3`, `trojan`, `shadowsocks`, `naiveproxy`, `tor`, `mieru`, `ssh`, `google_apps_script`, `snowflake`, `webtunnel`, `obfs4` | Kotlin `RelayKindDescriptors` + `*RelayKindResolver` registry; Rust `ripdpi-relay-core` descriptors for native-wired backends. `mieru` and `ssh` have native TCP session factories in `builders/{mieru,ssh}.rs`; both keep UDP disabled in the transport descriptors |
| **TCP chain step kind** | `StrategyTcpStep.kind` string — `split`, `syndata`, `seqovl`, `disorder`, `multidisorder`, `fake`, `fakedsplit`, `fakeddisorder`, `hostfake`, `oob`, `disoob`, `tlsrec`, `tlsrandrec`, `ipfrag2`, `fakerst` | Kotlin `TcpChainStepKind.wireName` (`StrategyChainProtobuf.kt`); Rust `parse_tcp_chain_step_kind` |
| **UDP chain step kind** | `StrategyUdpStep.kind` string | `UdpChainStepKind.wireName`; Rust `parse_udp_chain_step_kind` |
| **Fake/fingerprint profiles** | `tls_fake_profile`, `http_fake_profile`, `udp_fake_profile`, `quic_fake_profile`, `tls_fingerprint_profile` strings (value lists in `app_settings.proto` comments) | Rust `parse_*`; `ripdpi-tls-profiles` catalog |
| **Root-helper commands** | `CMD_*` constants in `ripdpi-root-helper-protocol/src/commands.rs`: `probe_capabilities`, `send_fake_tcp`, `send_fake_rst`, `send_flagged_tcp_payload`, `send_seqovl_tcp`, `send_multi_disorder_tcp`, `send_ordered_tcp_segments`, `send_ip_fragmented_tcp`, `send_ip_fragmented_udp`, `send_syn_hide_tcp`, `send_icmp_wrapped_udp`, `recv_icmp_wrapped_udp`, `send_raw_ip_packet`, `shutdown` | Helper binary `ripdpi-root-helper`; client `ripdpi-runtime-platform` |
| **Telemetry event domains** | `proxy`, `relay`, `warp`, `amneziawg`, `tunnel`, `diagnostics`, `monitor` (`android-support/src/events.rs`; `amneziawg` routes to the process-local WARP-family ring) | `NativeEventRecord` ring; Kotlin telemetry coordinators |
| **Telemetry event `kind`** | per-event `kind` strings, e.g. `runtime_ready` (read by Kotlin `NativeRuntimeSnapshot.nativeEvents`) | `ripdpi-telemetry`; Kotlin |

**Important compatibility behaviors:**

- An **unknown TCP/UDP step kind is rejected** by both Kotlin and Rust. Strategy steps are security-sensitive executable identifiers, so accepting the surrounding config while silently dropping an unknown step could change runtime strategy semantics. Environment-classification strings remain tolerant and fall back to `Unknown`; do not generalize that behavior to strategy identifiers.
- Some Rust wire names carry `#[serde(alias = …)]` so a historic spelling still
  decodes — preserve aliases when touching those structs.
- Telemetry payloads are golden-locked; an event-name or field change is a
  contract change. The runtime-telemetry ownership, stable identifiers, and
  forward-compatibility rules are documented in
  [`TELEMETRY_CONTRACT.md`](TELEMETRY_CONTRACT.md).

---

## 6. Support settings deep-link packages

Support settings deep links are a user-support contract layered on top of `AppSettings`. They do not add a separate persistence store; they decode a versioned package from `ripdpi://support-config` or `https://po4yka.github.io/RIPDPI/support-config`, stage the package against the current `AppSettings`, show the user a preview, and replace the stored settings only when every operation validates.

The address space is the generated top-level `AppSettings.Builder` surface. A support package writes paths as `settings.<field_name>`; the registry normalizes generated setter casing, snake_case, and kebab-case to `settings.<snake_case>`. New protobuf settings become support-link addressable automatically when the generated builder exposes a setter, and `SupportSettingsApplyUseCaseTest` asserts that every generated top-level setter has a support path.

Compatibility rules:

- Package `schema` must equal the current support package schema (`1`).
- Only `op: "set"` is accepted.
- Scalar values use JSON primitives; repeated string settings use JSON string arrays; protobuf-message settings such as chain steps use unpadded URL-safe Base64 of serialized protobuf bytes.
- Preview/apply is all-or-nothing. Unsupported paths, unsupported operations, invalid values, malformed packages, and unsupported schemas reject the whole package without writing settings.
- Sensitive paths must remain preview-visible. The registry flags explicit sensitive paths and any normalized path containing `token`, `credential`, `password`, `private_key`, or `keylog`.

Detailed package shape, link forms, limits, ownership, and focused tests live in [`docs/support-settings-deep-links.md`](../support-settings-deep-links.md).

---

## 7. Rules for additive settings

A new setting is **safe** only if all of the following hold:

1. **Protobuf:** new `AppSettings` field, next free number, defaulted, inert at
   its proto3 default value (§1).
2. **Kotlin:** added to the matching settings/section model and section codec
   with a Kotlin default; for a UI section, the section already has a default
   in `NativeProxyConfig.Ui` (`= NativeXxxConfig()`).
3. **Rust:** added to the consuming struct with `#[serde(default)]` (or a named
   `#[serde(default = "fn")]`) so a current-schema payload without the optional
   field receives the inert default (§3).
4. **Additive tolerance holds within the current schema:** an absent optional
   field loads with the inert default and an unknown field is ignored because
   the proxy/tunnel structs are not `deny_unknown_fields`.

**Never:** make a new field required; change an existing field's type, number,
name, or meaning; or change a default such that existing users' behavior
shifts silently. A new chain step *kind* is additive without a proto field (it
is a `kind` string), but unknown executable kinds remain rejected (§5).

---

## 8. Migration checklist — a setting that affects Rust runtime behavior

1. **Proto.** Add the field to `AppSettings` in `app_settings.proto`; next free
   number; document the unset sentinel in a trailing comment. If replacing a
   field, add the old number **and** name to the `reserved` lists.
2. **Kotlin settings model.** Add it to the `:core:data:model` /
   `:core:data:settings` model and the DataStore mapping; provide a default.
3. **Section codec.** Thread it through the matching
   `core/engine/.../core/codec/*SectionCodec.kt` and, if needed, the
   `NativeProxy*PreferencesMapper.kt`; it must serialize into the correct
   nested section of the `ui` payload in `RipDpiProxyJsonCodec`.
4. **Rust struct.** Add the field to the `ripdpi-proxy-config` /
   `ripdpi-config` / `ripdpi-tunnel-config` struct with `#[serde(default)]`;
   the default must preserve current inert behavior.
5. **Consume it.** Wire the field into `RuntimeConfig` construction
   (`ripdpi-proxy-config/src/convert/`) and the runtime that reads it.
6. **Replay.** Confirm `RipDpiProxyJsonCodec.rewriteJson` preserves the new
   field and unknown subtrees for current-schema remembered policies.
7. **Identifiers.** If the setting introduces a new enum-like string, register
   it per §5 and add the Rust `parse_*` arm; unknown executable identifiers
   remain fail-closed.
8. **Goldens.** Update the config-translation goldens under human supervision;
   if it touches diagnostics or telemetry payloads, follow those contracts'
   governance (see §9 and `DiagnosticsContractGovernanceTest`).
9. **Locales.** Any new UI string lands in the default `values/` file and all
   nine translations (`ru`, `es`, `de`, `fr`, `fa`, `ar`, `zh-rCN`, `hi`, `pt-rBR`) in
   the same commit.
10. **Support link.** Confirm the generated support-settings registry test covers the new top-level path; add explicit preview/apply tests for sensitive, repeated, or protobuf-message settings.
11. **Tests.** Protobuf round-trip test; codec/mapper test; Rust deserialization
    test proving a current-schema config with the optional field absent uses
    the inert default.

---

## 9. Native config schema versions

**Current state.** Versioning is explicit for native-facing JSON contracts:

- The **diagnostics engine** request/report/progress wire uses schema `5` on
  both Kotlin and Rust. `schemaVersion` is required; missing, older, and future
  versions are rejected. The bundled diagnostics catalog has an independent
  schema and is not part of this engine envelope.
- The **strategy-pack** config carries `LoadedStrategyConfig.version: u32`
  (`ripdpi-strategy-config`).
- The **relay native runtime config** carries required `schemaVersion: 10` on
  `ResolvedRipDpiRelayConfig`; Kotlin `RelayNativeConfigSchemaVersion` and Rust
  `SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION` match. Versions 6–9 are retired and
  missing or future versions are rejected. Version 10 requires explicit
  `tlsFingerprintProfile` values on the top-level relay config, every resolved
  chain hop, and every resolved ShadowTLS inner config; omission is a decode
  error rather than an implicit Chrome selection. The current chain-relay model is
  generalized from the fixed `chainEntry` /
  `chainExit` pair to an ordered, bounded hop list — `RelayChainSection.hops` is
  a `List<ResolvedChainRelayHopRef>` with `RelayChainMinHops = 2` ..
  `RelayChainMaxHops = 4`; a count outside that range raises the typed
  `RelayChainHopCountException` Kotlin-side and the typed `InvalidInput`
  `io::Error` from `ChainRelayConfig::validate_hop_count` at native build time
  (no silent truncation on either side). The flat wire DTO carries the ordered
  list **additively** as `chainHops` — Kotlin
  `ResolvedRipDpiRelayConfig.chainHops: List<ResolvedChainRelayHopConfig>`
  (annotated `@EncodeDefault(NEVER)`) mirroring Rust
  `FlatResolvedRelayRuntimeConfig::chain_hops: Vec<ResolvedChainRelayHopConfig>`
  (`#[serde(default)]`). When `chainHops` is populated it is the N-hop source of
  truth, so **3-/4-hop chains are expressible and consumed end-to-end across the
  wire**: `chainSection()` folds the list directly and
  `ChainRelayConfig::ordered_hops` returns it verbatim to the native builder. The
  `chainEntry*` / `chainExit*` scalar pair stays on the current wire as the
  **derived hop[0] / hop[last] mirror** (the
  `toResolvedConfig()` unfold projects it from the first/last hop; the Rust
  serialize path mirrors it the same way).
  A current two-hop payload may omit `chainHops`, but must carry resolved
  `chainEntry` and `chainExit` configs; the scalar fields are mirrors and are
  never synthesized into executable hops. The wire
  round-trip is covered by
  `RelayNativeConfigTest` (Kotlin `chainHops` 3-hop trip) and
  `ripdpi-relay-core::tests` (`chain_relay_three_hop_list_round_trips_through_flat_wire`,
  `chain_relay_wire_rejects_out_of_range_hop_count`).
- Relay native runtime config also carries the additive `socketProtection` enum (`inactive` / `vpn_required`). It is runtime-owned rather than persisted profile state: the proxy service always writes `inactive`, the VPN service always writes `vpn_required`, and Rust defaults a missing field to `inactive`. Schema 10 retains this inert default; only TLS fingerprint identity became mandatory. Dialers must use the value as policy; callback presence is only lifecycle state and must never decide whether protection is required.
- The **proxy native config** carries `schemaVersion` on every
  `NativeProxyConfig` variant. Kotlin `NativeProxyConfigSchemaVersion` and Rust
  `SUPPORTED_NATIVE_CONFIG_SCHEMA_VERSION` are both `2`; missing, version 1,
  and future payloads are rejected by `ripdpi-proxy-config`.
- The Android **tunnel JNI flat-JSON config** carries required
  `schemaVersion: 3` on `Tun2SocksConfig`. Kotlin validates the version before
  calling `Tun2SocksBindings.create`; the live `ripdpi-tunnel-android`
  `TunnelConfigPayload` validates it again before registering a native handle.
  Missing, retired version `2`, and future versions fail closed. The optional,
  additive `splitDnsPolicy` section is used by the native DNS interceptor;
  its ordered rules, digests, numeric resolver candidates, and bounded
  coverage-reason token are checked before native handle allocation. When the
  section is present, MapDNS and a complete encrypted resolver endpoint are
  mandatory, and `bootstrapPins` must exactly match the ordered top-level
  `encryptedDnsBootstrapIps`. Digests are immutable policy identity carried for
  diagnostics. `directResolverCandidates` execute only when Android supplies
  a validated, non-VPN, non-captive default-network lease whose numeric DNS set
  and binder-issued callback generation exactly match the policy. That
  generation is runtime-only: it is excluded from persistence, scope hashes,
  and the canonical routing digest. Missing, rejected, or stale leases use the
  configured encrypted resolver instead of the system default. This is
  intentionally distinct from the standalone `ripdpi-tunnel-config` YAML file
  format, which remains schema `2`; changing the JNI envelope does not change
  the YAML schema.

For proxy and tunnel payloads, `schemaVersion` is normally bumped **only** on a
genuinely breaking shape change — a field whose meaning changed, or a removed
section — never for an additive field. Tunnel JNI flat-JSON schema `3` is the
current fail-closed live Android adapter envelope; it does not supersede the
standalone YAML schema `2`. Additive changes stay covered by §7.

---

## Cross-references

| Topic | Source |
|-------|--------|
| Config flow overview | [`ARCHITECTURE.md`](ARCHITECTURE.md) §6 |
| Adding a setting / strategy end-to-end | [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) §1, §5 |
| The JNI boundary the JSON crosses | [`JNI_CONTRACT.md`](JNI_CONTRACT.md) |
| Connection policy & network memory | [`docs/native/README.md`](../native/README.md) |
| Golden bless discipline | [`.claude/rules/golden-bless-discipline.md`](../../.claude/rules/golden-bless-discipline.md) |
| Proto evolution / DataStore | `protobuf-schema-evolution`, `protobuf-datastore` skills |
| Diagnostics wire contract | `diagnostics-system` skill |
| Runtime telemetry events & snapshots | [`TELEMETRY_CONTRACT.md`](TELEMETRY_CONTRACT.md) |
