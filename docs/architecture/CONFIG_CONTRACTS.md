# Configuration Contracts

How a user setting travels from the protobuf schema, through Kotlin mappers and
the native JSON codec, into the Rust runtime config — and the **compatibility
rules** that keep every hop backward- and forward-safe.

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
  highest `AppSettings` number in use today is `285`; a new field takes the
  next free number.
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
  Rust proxy/tunnel structs are **not** `#[serde(deny_unknown_fields)]`, so an
  older Rust build silently ignores a key it does not know — additive keys are
  forward-safe.
- `environmentKind` is carried as the `EnvironmentKind` enum **variant name**
  string (`"Field"` / `"Emulator"` / `"Unknown"`); Rust parses it back into
  `ripdpi_config::EnvironmentKind`, defaulting unknown to `Unknown`.
- The config-translation JSON is covered by golden tests — treat a golden diff
  as a wire-contract change ([`.claude/rules/golden-bless-discipline.md`](../../.claude/rules/golden-bless-discipline.md)).

---

## 3. Rust config deserialization / defaulting rules

Owner: `ripdpi-proxy-config` (entry points `parse_proxy_config_json`,
`runtime_config_from_ui`, `runtime_config_from_command_line`,
`runtime_config_from_payload`), `ripdpi-config` (`src/model/`,
`src/model/defaults.rs`), `ripdpi-tunnel-config`.

- **Every field is defaulted.** The config crates carry ~235 `#[serde(default)]`
  attributes — effectively every deserialized field. A new field **must** be
  `#[serde(default)]` or `Option<T>`; a missing field must never fail
  deserialization on the proxy/tunnel path.
- **Custom defaults are named functions.** `#[serde(default = "fn")]` — e.g.
  `default_relay_server_port`, `default_tcp_chain_steps`,
  `default_seqovl_fake_mode`, `default_ipv6_extension_profile`,
  `default_fake_payload_profile`, `default_true`. The default must reproduce
  the *inert / pre-existing* behavior so an old config keeps working.
- **Unknown fields are tolerated** for proxy / tunnel / diagnostics config — no
  `#[serde(deny_unknown_fields)]`. The single exception is
  `ripdpi-strategy-config` (`src/lib.rs`, `#![forbid(unsafe_code)]`), which
  uses `deny_unknown_fields` for strict strategy-pack YAML/TOML parsing — a
  typo there is an error, by design.
- **Rename → keep an alias.** When a wire name must change, add
  `#[serde(alias = "oldName")]` rather than a hard rename. Precedent in the
  crates: `#[serde(rename = "tls_rec", alias = "tlsRec")]` and the matching
  `tls_rand_rec` / `tlsRandRec`.
- **The normalized shape is `RuntimeConfig`**, wrapped by `RuntimeConfigEnvelope
  { config, runtime_context, log_context, native_log_level }`
  (`ripdpi-proxy-config/src/types/payload.rs`).
- **String → enum parsing must fall back, not panic.** The `parse_*` helpers
  (`parse_desync_mode`, `parse_tcp_chain_step_kind`, `parse_tls_fake_profile`,
  `parse_quic_fake_profile`, `parse_quic_initial_mode`, `parse_http_fake_profile`,
  `parse_udp_chain_step_kind`, `parse_udp_fake_profile`) decode the stable
  identifier strings from §5; an unrecognized value must resolve to a
  documented safe default, never abort the whole config.

---

## 4. Exact config replay rules

Per-network winners are persisted and replayed verbatim — see
[`docs/native/README.md`](../native/README.md) § Connection Policy and Network
Memory.

- **What is persisted:** `remembered_network_policies` stores the exact
  normalized `proxyConfigJson` (plus an optional VPN DNS override and the
  TCP/QUIC/DNS strategy-family labels) for a validated network winner. On
  reconnect that JSON is replayed.
- **The strategy body is the identity; the context is not.** Before persistence
  the volatile context is stripped — `RipDpiProxyJsonCodec.stripRuntimeContext`
  removes `runtimeContext` and `logContext`. On replay,
  `RipDpiProxyJsonCodec.rewriteJson` decodes the stored JSON and re-applies the
  *current* `hostAutolearnStorePath`, `networkScopeKey`, `runtimeContext`,
  `logContext`, `rootMode`, geo-DB paths, and session overrides, then
  re-encodes. Session overrides (local listen-port override, auth token) are
  merged fresh via `SessionOverrideCodec.merge` — they are never part of the
  persisted identity.
- **Replay invariant:** a stored `proxyConfigJson` must decode and re-encode to
  the same strategy semantics on the current build. This is why §2's
  no-rename rule is absolute — a renamed key silently drops persisted strategy
  state for every remembered network.
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
| **Relay kind** | `relay_kind` (proto field 171): `off`, `vless_reality`, `hysteria2`, `chain_relay`, `masque`, `cloudflare_tunnel`, `tuic_v5`, `shadowtls_v3`, `naiveproxy`, `google_apps_script` | Kotlin `*RelayKindResolver` + `RelayKindResolverRegistry`; Rust relay-core |
| **TCP chain step kind** | `StrategyTcpStep.kind` string — `split`, `seqovl`, `disorder`, `multidisorder`, `fake`, `fakesplit`, `fakedisorder`, `hostfake`, `oob`, `disoob`, `tlsrec`, `tlsrandrec`, `ipfrag2` | Kotlin `TcpChainStepKind.wireName` (`StrategyChainProtobuf.kt`); Rust `parse_tcp_chain_step_kind` |
| **UDP chain step kind** | `StrategyUdpStep.kind` string | `UdpChainStepKind.wireName`; Rust `parse_udp_chain_step_kind` |
| **Fake/fingerprint profiles** | `tls_fake_profile`, `http_fake_profile`, `udp_fake_profile`, `quic_fake_profile`, `tls_fingerprint_profile` strings (value lists in `app_settings.proto` comments) | Rust `parse_*`; `ripdpi-tls-profiles` catalog |
| **Root-helper commands** | `CMD_*` constants in `ripdpi-root-helper-protocol/src/commands.rs`: `probe_capabilities`, `send_fake_tcp`, `send_fake_rst`, `send_flagged_tcp_payload`, `send_seqovl_tcp`, `send_multi_disorder_tcp`, `send_ordered_tcp_segments`, `send_ip_fragmented_tcp`, `send_ip_fragmented_udp`, `send_syn_hide_tcp`, `send_icmp_wrapped_udp`, `recv_icmp_wrapped_udp`, `send_raw_ip_packet`, `shutdown` | Helper binary `ripdpi-root-helper`; client `ripdpi-runtime-platform` |
| **Telemetry event domains** | `proxy`, `relay`, `warp`, `tunnel`, `diagnostics`, `monitor` (`android-support/src/events.rs`) | `NativeEventRecord` ring; Kotlin telemetry coordinators |
| **Telemetry event `kind`** | per-event `kind` strings, e.g. `runtime_ready` (read by Kotlin `NativeRuntimeSnapshot.nativeEvents`) | `ripdpi-telemetry`; Kotlin |

**Important compatibility behaviors:**

- An **unknown TCP/UDP step kind is silently dropped** — `StrategyChainProtobuf.kt`
  decodes via `TcpChainStepKind.fromWireName(...)` inside a `mapNotNull`. An old
  app build will quietly skip a step kind a newer build wrote. Account for this
  when persisting strategies that must survive a downgrade.
- Some Rust wire names carry `#[serde(alias = …)]` so a historic spelling still
  decodes — preserve aliases when touching those structs.
- Telemetry payloads are golden-locked; an event-name or field change is a
  contract change. The runtime-telemetry ownership, stable identifiers, and
  forward-compatibility rules are documented in
  [`TELEMETRY_CONTRACT.md`](TELEMETRY_CONTRACT.md).

---

## 6. Rules for additive settings

A new setting is **safe** only if all of the following hold:

1. **Protobuf:** new `AppSettings` field, next free number, defaulted, inert at
   its proto3 default value (§1).
2. **Kotlin:** added to the matching settings/section model and section codec
   with a Kotlin default; for a UI section, the section already has a default
   in `NativeProxyConfig.Ui` (`= NativeXxxConfig()`).
3. **Rust:** added to the consuming struct with `#[serde(default)]` (or a named
   `#[serde(default = "fn")]`) so older JSON without the field still loads (§3).
4. **Both downgrade and upgrade work:** an old config (field absent) loads with
   the inert default; an old binary (field unknown) ignores it — true today
   because the proxy/tunnel structs are not `deny_unknown_fields`.

**Never:** make a new field required; change an existing field's type, number,
name, or meaning; or change a default such that existing users' behavior
shifts silently. A new chain step *kind* is additive without a proto field (it
is a `kind` string) — but an old Rust build will drop it (§5).

---

## 7. Migration checklist — a setting that affects Rust runtime behavior

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
   the default must reproduce pre-existing behavior.
5. **Consume it.** Wire the field into `RuntimeConfig` construction
   (`ripdpi-proxy-config/src/convert/`) and the runtime that reads it.
6. **Replay.** Confirm `RipDpiProxyJsonCodec.rewriteJson` round-trips the new
   field — a remembered policy persisted before this change must still load
   (the field reads as its default), and one persisted after must replay it.
7. **Identifiers.** If the setting introduces a new enum-like string, register
   it per §5 and add the Rust `parse_*` arm with a safe fallback.
8. **Goldens.** Update the config-translation goldens under human supervision;
   if it touches diagnostics or telemetry payloads, follow those contracts'
   governance (see §8 and `DiagnosticsContractGovernanceTest`).
9. **Locales.** Any new UI string lands in all 7 locale files in the same
   commit.
10. **Tests.** Protobuf round-trip test; codec/mapper test; Rust deserialization
    test proving an old config (field absent) still loads.

---

## 8. Future improvement — a `schemaVersion` envelope (documented, not implemented)

**Do not implement this now.** It is recorded here as a design direction.

**Current state.** Versioning is *partial*:

- The **diagnostics** wire contract is explicitly versioned —
  `DIAGNOSTICS_ENGINE_SCHEMA_VERSION: u32 = 1`
  (`ripdpi-diagnostics-contracts/src/wire.rs`), serialized as `schemaVersion`
  with a `default_schema_version()` serde default, mirrored Kotlin-side by
  `DiagnosticsEngineSchemaVersion` and `BundledDiagnosticsCatalogSchemaVersion`,
  and policed by `DiagnosticsContractGovernanceTest`.
- The **strategy-pack** config carries `LoadedStrategyConfig.version: u32`
  (`ripdpi-strategy-config`).
- The **proxy / tunnel native config JSON has no version field.**
  `NativeProxyConfig` is discriminated only by `kind` (`command_line` / `ui`);
  `RuntimeConfigEnvelope { config, runtime_context, log_context,
  native_log_level }` carries no version. Compatibility today rests entirely on
  `serde(default)` tolerance and the no-rename rule.

**Suggested envelope.** Add a small integer `schemaVersion` to the proxy/tunnel
config envelope, mirroring the diagnostics precedent:

```jsonc
// illustrative shape only — NOT a change to apply
{
  "schemaVersion": 1,        // serde(default = "default_schema_version")
  "kind": "ui",
  "listen": { ... },
  ...
}
```

- `schemaVersion` defaults (via `#[serde(default = …)]`) so every existing JSON
  blob and every persisted remembered policy reads as version `1`.
- It is bumped **only** on a genuinely breaking shape change — a field whose
  meaning changed, or a removed section — never for an additive field (additive
  changes stay covered by §6).
- It lets the native side detect a config produced by a newer or older app
  build and pick an explicit migration path, instead of silently relying on
  default-tolerance.
- It should be surfaced to a governance test analogous to
  `DiagnosticsContractGovernanceTest`.

**Benefit:** turns the proxy-config compatibility story from implicit
("`serde(default)` happens to absorb it") into explicit and testable. **Cost:**
a migration shim per breaking version and one more golden surface — which is
why it is deferred, not adopted here.

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
