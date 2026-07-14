# Kotlin-Rust boundary contract audit — 2026-07-10

## Audited scope

This audit covered Kotlin external declarations and Rust JNI exports for proxy, tunnel, relay, WARP, AmneziaWG, diagnostics, strategy, capability, ECH, fetch, and shared-prior surfaces; proxy/tunnel/relay native-config JSON; remembered proxy-policy persistence and replay; `AppSettings` protobuf/DataStore behavior; service config consumers; diagnostics request/report/progress JSON; runtime telemetry payloads and event domains; relay backend schema projection; and committed field manifests, golden fixtures, and cross-language release gates. Eight explicit read-only specialist lanes audited JNI exports, native config JSON, protobuf/DataStore, relay schema, diagnostics wire schema, telemetry contracts, remembered-policy replay, and golden-test coverage before coordinator consolidation.

## Findings fixed

1. Native lifecycle setup is enclosed by cleanup from the first readiness-listener registration onward for proxy, relay, WARP, and AmneziaWG. Tunnel start failures attempt `stop` before `destroy`, unregister flow attribution, and retire the Kotlin handle even when setup or cancellation fails.
2. JNI parity is structurally locked: every audited Kotlin `external fun` has a matching Rust `Java_*` export, no meaningful orphan export remains, panic boundaries and reviewed `JNI_OnLoad` exceptions are accounted for, and stable method/library identifiers were not renamed.
3. Proxy JSON decoding tolerates additive unknown keys while schema versions and executable TCP/UDP strategy identifiers remain fail-closed. Kotlin no longer silently drops unknown chain steps, and unknown remembered-policy subtrees survive rewrite/persistence stripping.
4. Remembered proxy JSON rewrite patches the original JSON tree rather than decoding and re-encoding a typed subset. Volatile runtime/log/session fields are stripped before persistence and refreshed at replay; a matched payload that cannot materialize is failure-counted and falls back to baseline instead of aborting startup.
5. Rust consumes the Kotlin adaptive strategy-evolution fields with Kotlin-compatible defaults and bounded signed-to-unsigned conversion. `evolutionEpsilon` retains its floating-point wire form while runtime storage uses permil.
6. The historical custom AppSettings xHTTP tag migration was removed. Tag 214 remains the current `strategy_chain_yaml`; retired 215/216 remain reserved; fields 258–260 remain the current relay xHTTP fields; ordinary protobuf unknown fields still round-trip through the DataStore serializer.
7. Proxy and tunnel native configs require current schema 2 on Kotlin and Rust. Relay native config requires current schema 10; schemas 6–9, missing versions, and future versions are rejected. Schema 10 additionally requires explicit TLS fingerprint identity on the top level, every chain hop, and nested ShadowTLS config.
8. ShadowTLS nested VLESS config carries `vlessFlow` through the flat relay DTO, backend builders, and TLS transport client; explicit flow is preserved and current omission receives the current Vision default.
9. Diagnostics Kotlin models cover Rust's emitted freeze status, DNS/TCP evidence, alternate ports, strategy seeds, and per-candidate domain outcomes. Engine request/report/progress schema is required current-only version 5 on both sides; missing, older, and future versions fail closed. Test-persisted reports use the engine wire DTO instead of an unversioned domain model.
10. Runtime telemetry requires schema 3 from proxy, tunnel, relay, WARP, and AmneziaWG producers. All five Kotlin wrappers validate through one decoder and reject missing, older, and future payloads while retaining additive unknown-key tolerance. `diagnosticsSessionId` remains internal to `NativeEventRecord` and native tracing/log context; it was removed from Rust/Kotlin telemetry DTOs, field manifests, and approved goldens, with a regression test proving an internal non-null value is not serialized.
11. Cross-language governance expanded from 17 to 25 executable gates. Current schema owners, negative schema tests, all five telemetry producers, native telemetry goldens, protobuf unknown-field behavior, and the internal-only diagnostics-session invariant are listed in the release-gate inventory.

## Findings intentionally not fixed

- Current-version optional-field defaults and unknown-field tolerance are retained except for relay TLS fingerprint identity: relay schema 10 requires explicit top-level, chain-hop, and ShadowTLS-inner `tlsFingerprintProfile` fields so sparse producers cannot silently select Chrome.
- The relay two-hop `chainEntry*`/`chainExit*` scalar mirror was retained for diagnostics and field continuity, but scalar-only chains are no longer executable. Schema 10 requires resolved `chainEntry` and `chainExit` configs with explicit fingerprints when `chainHops` is omitted.
- Diagnostics archive/profile/catalog and backup schemas are independent persisted/export formats, not the audited Kotlin↔Rust engine wire. Their migration support was not removed.
- Relay/WARP/AmneziaWG do not yet have the same state-rich golden families as proxy/tunnel. Their schema and privacy behavior is unit-tested and now release-gated, but adding broader state goldens remains a separately reviewed fixture expansion.
- No live internet, third-party scan, rooted-device, emulator, or physical-device test was run. All evidence is deterministic and repository-owned.

## Migration and backward-compatibility statement

This change intentionally ends legacy compatibility at the audited native boundary. Proxy schema 1, tunnel schema 1, relay schemas 6–9, diagnostics engine schema 2, telemetry schema 1, and payloads missing those schema envelopes are unsupported. Kotlin and Rust require proxy 2, tunnel 2, relay 10, diagnostics engine 3, and telemetry 2. Old remembered proxy JSON is not migrated: it is rejected, failure-counted, suppression-aware, and replaced with baseline for startup. Historical AppSettings xHTTP wire tags are not semantically reinterpreted; protobuf reservations and standard unknown-field preservation remain. Stable keys, JNI identifiers, telemetry domains/kinds, protobuf field names/numbers, and executable strategy identifiers were not renamed. Additive unknown fields remain tolerated within the current schema, and current optional omissions retain inert defaults except for required relay TLS fingerprint identity fields.

Existing settings-generated runtime configs are regenerated with current versions. The intentional user-visible breaks are limited to retired remembered payloads/native binaries and historical xHTTP bytes from the ambiguous tag window; those users receive baseline behavior or must reconfigure rather than receiving a guessed migration.

## Tests and reproducibility

All tests used local code and repository-owned fixtures; no live network was used.

```text
python3 scripts/ci/check_cross_language_runtime_contracts.py
python3 -m unittest scripts.tests.test_cross_language_runtime_contracts
python3 scripts/ci/check_ffi_panic_boundary.py
python3 scripts/ci/check_ffi_headers.py
python3 scripts/ci/check_unsafe_boundaries.py
python3 scripts/ci/check_native_architecture_contracts.py
./gradlew :core:engine:testDebugUnitTest :core:data:testDebugUnitTest :core:service:testDebugUnitTest :core:diagnostics:testDebugUnitTest -Pripdpi.skipNativeBuild=true
CARGO_TARGET_DIR=/tmp/ripdpi-boundary-contract-audit-target-v1 cargo test --locked -p ripdpi-proxy-config --lib
CARGO_TARGET_DIR=/tmp/ripdpi-boundary-contract-audit-target-v1 cargo test --locked -p ripdpi-tunnel-config --lib
CARGO_TARGET_DIR=/tmp/ripdpi-boundary-contract-audit-target-v1 cargo test --locked -p ripdpi-relay-core --lib
CARGO_TARGET_DIR=/tmp/ripdpi-boundary-contract-audit-target-v1 cargo test --locked -p ripdpi-diagnostics-contracts --lib
CARGO_TARGET_DIR=/tmp/ripdpi-boundary-contract-audit-target-v1 cargo test --locked -p ripdpi-telemetry --lib
CARGO_TARGET_DIR=/tmp/ripdpi-boundary-contract-audit-target-v1 cargo test --locked -p android-support -p ripdpi-android-telemetry-adapter -p ripdpi-tunnel-android -p ripdpi-relay-android -p ripdpi-warp-android -p ripdpi-amneziawg-android --lib
cargo fmt --manifest-path native/rust/Cargo.toml --all -- --check
cargo metadata --locked --manifest-path native/rust/Cargo.toml --format-version 1 --no-deps
python3 scripts/ci/check_architecture_health.py
```

Results: cross-language governance passed with 5 surfaces and 25 gates; its Python suite passed 10/10. FFI checks found 71 extern definitions, 66 panic-contained exports and 5 reviewed allowlisted exports, no unwind-capable ABI, no header-hygiene violation, no unallowlisted unsafe-boundary pattern, and no native-architecture violation. The combined Kotlin engine/data/service/diagnostics unit-test command passed. Isolated Rust suites passed: proxy-config 119/119, tunnel-config 25/25, relay-core 103/103, diagnostics-contracts 51/51, and telemetry 39/39. The combined adapter run passed android-support 33/33, AmneziaWG 6/6, proxy telemetry adapter 17/17, relay 4/4, tunnel 74/74 with one explicitly ignored startup-latency smoke test, and WARP 4/4. The tunnel telemetry event assertion now uses a per-test capture buffer, eliminating a global-ring race exposed by the combined parallel test process. Repository pre-commit hooks also ran Kotlin/Rust formatting, Clippy, architecture delta, secrets, baseline, and native-contract checks on each relevant atomic commit.

## Residual risks

- Historical AppSettings bytes from the retired xHTTP tag collision are decoded only by the current protobuf schema. Affected users may need to reconfigure relay xHTTP settings; the application will not guess between current strategy YAML and historical meanings.
- Retired remembered proxy configs deliberately lose their remembered strategy for that network and fall back to baseline. Suppression accounting prevents repeated invalid replay, but the optimized winner is not automatically reconstructed.
- Current-only enforcement means mixing an updated Kotlin app with a retired native library, or replaying retired native payload fixtures, fails closed rather than degrading silently.
- Structural JVM/Rust/JNI tests do not replace loading built Android `.so` files across every ABI on a device. Device/ABI packaging remains a release-pipeline responsibility.
- Unknown JSON subtrees are preserved semantically, not byte-for-byte formatting or key order.
- A first relay test attempt against the shared Cargo target observed cross-worktree artifact contamination from a newer socket-protection API. The authoritative rerun uses a fresh isolated `CARGO_TARGET_DIR`; source files were clean and unchanged.

## Files changed

Changes are confined to scoped Kotlin engine/data/settings/service/diagnostics models and tests; scoped Rust proxy/tunnel/relay/diagnostics/telemetry/Android adapter crates; contract fixtures and approved goldens; architecture and audit documentation; the task record; and the machine-readable cross-language release-gate inventory. No release credential, signing configuration, secret, user-data file, unrelated baseline, or native binary was modified.
