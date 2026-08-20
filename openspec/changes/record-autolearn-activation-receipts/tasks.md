# DGN-1787230878672684: Record authoritative Autolearn activation receipts

## Objective

Publish ready-time proxy telemetry before `Connected` and retain privacy-safe, runtime-correlated Autolearn activation evidence for initial starts and proxy-runtime replacements.

## Ownership

Implementation ownership is limited to `core/service`, focused archive regression tests in `core/diagnostics`, and this OpenSpec change. No serialized schema, golden fixture, locale, protobuf, JNI, Rust, or generated-file lane is owned by this change.

## Execution

- [x] SVC-1787231291789069 Return the authoritative ready-time `NativeRuntimeSnapshot` with the proxy endpoint through `ProxyRuntimeSupervisor`, shared stack, and VPN composition; add and run the focused RED/GREEN supervisor and composition unit tests #feature !high @item:DGN-1787230878672684
- [x] SVC-1787231291791410 Publish typed runtime-start evidence before `Connected` for both proxy and VPN services with explicit `Snapshot`/`NoData` component statuses; prove callback ordering and same-runtime telemetry through lifecycle unit tests #feature !high @item:DGN-1787230878672684
- [x] SVC-1787231291794470 Implement the privacy-safe Autolearn receipt classifier, session generation, canonical event mapping, synchronous best-effort recorder, and initial/replacement-path emission; cover baseline, remembered, command-line, mismatch, cancellation, and storage-failure behavior #feature !high @item:DGN-1787230878672684
- [x] DGN-1787231291796653 Prove short-session retention and archive redaction of Autolearn activation events without a Room or archive-schema change, then run affected module suites, `staticAnalysis`, architecture health, and locked Cargo metadata #feature !high @item:DGN-1787230878672684

## Verification

- `./gradlew :core:service:testDebugUnitTest -Pripdpi.skipNativeBuild=true` for supervisor, lifecycle, recorder, and runtime-replacement behavior.
- `./gradlew :core:diagnostics:testDebugUnitTest -Pripdpi.skipNativeBuild=true` for archive retention and redaction.
- `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true` for Kotlin/Android static gates.
- `python3 scripts/ci/check_architecture_health.py` and `cargo metadata --manifest-path native/rust/Cargo.toml --locked --no-deps --format-version 1` on the combined tree.
- Hosted CI, device, APK/native artifact, and deployment evidence remain separate and are not implied by local gates.
