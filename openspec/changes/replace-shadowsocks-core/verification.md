---
task_id: RLY-1793949568813382
change: replace-shadowsocks-core
commit_sha: fe038f283d6a07c4e9a1aa7b14b3602670f7cee6
local: passed
local_evidence: 282 Rust integration and policy tests, seven upstream unit tests, 5861 Kotlin tests, staticAnalysis, targeted Clippy, cargo deny, source gates, and independent review passed.
remote_ci: required
remote_ci_evidence: Source revision fe038f283d6a07c4e9a1aa7b14b3602670f7cee6 is on origin/main. Hosted checks are running at https://github.com/po4yka/RIPDPI/actions/runs/42189829716.
device: not_applicable
device_evidence: This change has no UI or JNI field changes. Android compilation is checked separately; no physical-device acceptance is claimed.
artifact: passed
artifact_evidence: Android arm64 native libraries compiled and verify_native_elfs.py passed. No physical-device or external-network acceptance is claimed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SS-UPSTREAM | RLY-1793949806194236 | Six TCP methods, three SS2022 UDP methods, and AES identity-key fixture matrices | Passed |
| REQ-SS-PROTECTION | RLY-1793949806934877 | Supplied transport, protected sockets, half-close, cancellation, and split-task readiness tests | Passed |
| REQ-SS-REPLAY | RLY-1793949806934877 | Invalid packet, session binding, calibrated clock, upstream salt replay, and bounded session-window tests | Passed |
| REQ-SS-VALIDATION | RLY-1793949807671423 | Targeted tests, staticAnalysis, source gates, dependency checks, Android ELF checks, and independent review | Passed |

## Local evidence

- Rust 1.98.1: `cargo test --locked -p ripdpi-shadowsocks -p ripdpi-relay-tls-transports -p local-network-fixture -p ripdpi-relay-core --jobs 3` passed 282 tests. Existing ignored stress tests were not run. The final adapter repeat passed all 247 TLS and relay-core tests.
- The vendored library passed seven unit tests with `cargo test --locked --manifest-path vendor/shadowsocks/Cargo.toml --lib --no-default-features --features aead-cipher,aead-cipher-2022,security-replay-attack-detect --jobs 3`.
- Targeted all-target Clippy passed with `--no-deps -- -D warnings`. `cargo deny --locked check`, locked workspace and fuzz metadata, and source gates passed.
- Native Android arm64 libraries compiled through Gradle. `python3 scripts/ci/verify_native_elfs.py --lib-dir core/engine/build/generated/jniLibs --abis arm64-v8a` passed.
- Independent general, async cancellation, and unsafe-code reviews found no remaining actionable defects after fixes. Miri was not run.
- `integration.patch` was applied to the checksum-verified upstream package in a temporary directory; all resulting source files matched the vendored tree.
- The chain validation tests reproduced an untyped early rejection. The fix returns `RelayConfigRejected` from the single credential check; the regression assertions remain unchanged.

All compiler-backed local commands used the machine build gate. Rust commands ran from `native/rust` to select the pinned toolchain.

The successful import test now uses a valid 32-byte SS2022 key instead of an arbitrary password. All activation assertions are retained.

The final Kotlin gate passed with `build-gate -- env -u CARGO_BUILD_JOBS ./gradlew :core:data:runtime-state:testDebugUnitTest :core:service:testDebugUnitTest :app:testGithubFullDebugUnitTest staticAnalysis --max-workers=3 -Pripdpi.nativeCpuBudget=3 -Pripdpi.skipNativeBuild=true --no-daemon`. Native compilation and ELF verification passed separately before this repeat.

After rebase onto `2cf13604ef0ac2ba311f0e8a06085abb0134a7a4`, the 282 Shadowsocks/relay/fixture tests and 78 HTTP tests passed. Architecture health, locked metadata, and `just ci-gates` passed on the combined tree. Main integration used fast-forward; push succeeded.
