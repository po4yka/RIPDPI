# Kotlin-Rust boundary contract audit — 2026-07-10

## Audited scope

This audit covered the Kotlin external-function declarations and Rust JNI exports for the proxy, tunnel, relay, WARP, AmneziaWG, diagnostics, strategy, capability, ECH, fetch, and shared-prior surfaces; proxy native-config JSON codecs and remembered replay; `AppSettings` protobuf/DataStore persistence; service-side config consumers; relay schema versions 6 through 8 and sparse serializer defaults; diagnostics request/report/progress JSON; runtime telemetry routing and payload decoding; and the existing contract fixtures, golden manifests, and cross-language CI gates. Eight independent read-only audit lanes were used before consolidation: JNI exports, native config JSON, protobuf/DataStore, relay schema, diagnostics wire schema, telemetry contracts, remembered-policy replay, and golden-test coverage.

## Findings fixed

1. Native lifecycle setup is now enclosed by cleanup from the first readiness-listener registration onward for proxy, relay, WARP, and AmneziaWG. Tunnel start failures now attempt `stop` before `destroy`, unregister flow attribution, and retire the Kotlin handle even when setup or cancellation fails.
2. Proxy JSON decoding now tolerates additive unknown keys while schema versions and TCP/UDP strategy identifiers remain fail-closed. Kotlin rejects proxy schema versions other than 1 and no longer silently drops unknown chain steps.
3. Remembered proxy JSON rewrite and persistence stripping now patch the original JSON tree instead of decoding and re-encoding the typed subset. Unknown top-level and nested strategy fields survive replay; runtime/log/session overrides and host-autolearn device paths are removed before persistence and refreshed at replay time.
4. Rust now consumes all Kotlin adaptive strategy-evolution fields with Kotlin-compatible defaults and bounded signed-to-unsigned conversion. `evolutionEpsilon` retains its floating-point wire form while the runtime stores permil.
5. The historical protobuf collision at tags 214–216 now has an idempotent raw-wire migration. Current field 214 remains `strategy_chain_yaml`; retired historical tags 215 and 216 are reserved; legacy xHTTP transport/path/host values populate fields 258–260 only when current values are blank; unrelated unknown protobuf fields survive.
6. Sparse relay JSON omitted by Kotlin's `encodeDefaults = false` now receives matching Rust defaults for outbound bind, QUIC flags, TLS fingerprint, VLESS transport, Cloudflare mode, TUIC congestion control, Mieru fields, SSH auth, and Tor pluggable-transport fields. Relay versions below 6 and above 8 remain rejected.
7. ShadowTLS nested VLESS config now carries `vlessFlow` through the flat relay DTO, backend builders, and TLS transport client; absent nested flow defaults to Vision and explicit `xtls-rprx-vision-udp443` is preserved.
8. Kotlin diagnostics models now cover Rust's `FREEZE_AFTER_THRESHOLD`, all emitted DNS/TCP evidence fields, TCP alternate ports, per-domain strategy seeds, and per-candidate domain outcomes. Report and progress decoders accept absent/current schema v2 and reject explicit old/future versions.
9. Runtime telemetry decoding is tolerant of additive top-level and nested event fields. The `amneziawg` routing domain is accepted on the process-local WARP-family ring and AWG snapshots drain their native events without exposing endpoint host or port.
10. A matched remembered policy that fails materialization or validation now records a policy failure and falls back to the baseline configuration instead of bypassing suppression accounting and aborting startup.

## Findings intentionally not fixed

- `NativeEventRecord.diagnostics_session_id` remains captured internally but is not added to the serialized Kotlin event DTOs. The full engine suite proved that adding it changes the approved proxy/tunnel golden field manifests; the golden-bless discipline requires a separate human-approved contract migration.
- Diagnostics field-manifest samples still do not populate every optional report branch, and telemetry release fixtures still focus on proxy/tunnel rather than adding relay/WARP/AWG golden families. Expanding those committed fixture surfaces would intentionally change shared goldens, so this audit records the gap without running `RIPDPI_BLESS_GOLDENS=1`.
- The JNI audit gate proves 76 Kotlin external functions match 76 Rust `Java_*` exports across five libraries, but there is no separate checked-in exact symbol manifest for every library. No mismatch or meaningful orphan export was found, so adding a second manifest mechanism was left as an advisory hardening task.

## Migration and backward compatibility

No stable JSON key, JNI method, telemetry domain/kind, protobuf field name, or protocol identifier was renamed, and no schema version was bumped. Proxy payloads with an absent schema version or version 1 remain accepted; explicit other versions are rejected on both sides. Relay payloads with absent schema version default to 8, versions 6 through 8 remain accepted, and values outside that range are rejected. Diagnostics report/progress payloads with absent/current version decode as v2 while explicit v1/future versions fail closed. Old protobuf relay xHTTP bytes migrate only under strong legacy evidence, current fields always win, legitimate current `strategy_chain_yaml` content is preserved, and unknown protobuf fields remain round-trippable. New or previously omitted JSON fields are defaulted on both Kotlin and Rust sides. Non-root behavior, root capability gates, credentials, signing configuration, user data, and release secrets were not changed.

## Tests and reproducibility

All network behavior was tested with unit, structural, and repository-owned fixtures; no live internet or third-party scanning was used. The following commands passed in the isolated worktree:

```text
python3 scripts/ci/check_cross_language_runtime_contracts.py
python3 -m unittest scripts.tests.test_cross_language_runtime_contracts
python3 scripts/ci/check_ffi_panic_boundary.py
python3 scripts/ci/check_ffi_headers.py
python3 scripts/ci/check_unsafe_boundaries.py
./gradlew :core:data:settings:testDebugUnitTest --tests 'com.poyka.ripdpi.data.AppSettingsSerializerMigrationTest' -Pripdpi.skipNativeBuild=true
./gradlew :core:engine:testDebugUnitTest -Pripdpi.skipNativeBuild=true
./gradlew :core:data:testDebugUnitTest -Pripdpi.skipNativeBuild=true
./gradlew :core:service:testDebugUnitTest --tests 'com.poyka.ripdpi.services.ConnectionPolicyResolverTest' -Pripdpi.skipNativeBuild=true
./gradlew :core:diagnostics:testDebugUnitTest --tests 'com.poyka.ripdpi.diagnostics.Diagnostics*Contract*Test' --tests 'com.poyka.ripdpi.diagnostics.DiagnosticsEngineSchemaValidationTest' -Pripdpi.skipNativeBuild=true
cargo test --locked --manifest-path native/rust/Cargo.toml -p ripdpi-proxy-config --lib
cargo test --locked --manifest-path native/rust/Cargo.toml -p ripdpi-relay-core --lib
cargo test --locked --manifest-path native/rust/Cargo.toml -p ripdpi-diagnostics-contracts --lib
cargo test --locked --manifest-path native/rust/Cargo.toml -p ripdpi-telemetry --lib
cargo test --locked --manifest-path native/rust/Cargo.toml -p android-support -p ripdpi-android-telemetry-adapter -p ripdpi-relay-android -p ripdpi-warp-android -p ripdpi-tunnel-android -p ripdpi-amneziawg-android --lib
```

The four requested Rust suites passed with 118, 104, 50, and 39 tests respectively. The cross-language script validated 5 surfaces and 17 gates; its Python suite ran 10 tests. FFI checks found 71 extern definitions, 66 contained panic boundaries plus 5 reviewed `JNI_OnLoad` exceptions, no unwind ABI, no header hygiene violation, and no unallowlisted unsafe-boundary pattern. The broad engine suite initially failed exactly as intended when an unapproved telemetry field changed a golden manifest; that field projection was removed and the unchanged golden suite then passed.

## Residual risks

- Protobuf tag 214 is historically ambiguous because it was reused before this migration existed. A payload containing only the exact legacy transport token with no compatible enabled relay context is conservatively left as strategy YAML; this avoids corrupting legitimate current data but may require manual reconfiguration for that narrow legacy state.
- Structural Kotlin/Rust tests do not replace an instrumented Android device run against built `.so` files. Static symbol parity, JVM unit tests, Rust unit tests, and JNI contract scripts are green, but device/ABI loading remains a release-pipeline responsibility.
- Forward compatibility preserves unknown JSON fields semantically, not byte-for-byte formatting or object-key order. Strategy arrays, identifiers, values, and unknown subtrees are preserved.
- The approved telemetry and diagnostics golden families have the coverage gaps listed above; those gaps reduce drift detection for optional branches until a separately reviewed fixture expansion lands.

## Files changed

Changes are confined to `core/engine`, `core/engine-api` consumption paths, `core/data/model`, `core/data/settings`, `core/diagnostics`, `core/service`, the scoped Rust crates under `native/rust/crates/`, contract documentation, and task/audit records. No baseline, golden fixture, credential, signing, secret, or user-data file was modified.
