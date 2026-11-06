---
task_id: RLY-1793949568813382
change: replace-shadowsocks-core
commit_sha: 83ec7d0ddb8b63899700e6213ebf6d4233a449cd
local: passed
local_evidence: 282 Rust integration and policy tests, seven upstream unit tests, 5861 Kotlin tests, staticAnalysis, targeted Clippy, cargo deny, source gates, and independent review passed.
remote_ci: blocked
remote_ci_evidence: CI run 42198808996 failed on the combined main tree due to the DNS instrumentation call and existing native size budget overruns. Shadowsocks source and the formatting correction are integrated; the task remains in review.
device: not_applicable
device_evidence: This change has no UI or JNI field changes. Android compilation is checked separately; no physical-device acceptance is claimed.
artifact: blocked
artifact_evidence: Native libraries compiled and ELF checks passed. Packaged native size checks remain blocked; old pre-migration artifacts fail the same current limits. No baseline was changed.
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
| REQ-SS-VALIDATION | RLY-1793949807671423 | Targeted tests, staticAnalysis, source gates, dependency checks, Android ELF checks, and independent review | Local passed; hosted blocked |

## Local evidence

- Rust 1.98.1: `cargo test --locked -p ripdpi-shadowsocks -p ripdpi-relay-tls-transports -p local-network-fixture -p ripdpi-relay-core --jobs 3` passed 282 tests. Existing ignored stress tests were not run. The final adapter repeat passed all 247 TLS and relay-core tests.
- The vendored library passed seven unit tests with `cargo test --locked --manifest-path vendor/shadowsocks/Cargo.toml --lib --no-default-features --features aead-cipher,aead-cipher-2022,security-replay-attack-detect --jobs 3`.
- Targeted all-target Clippy passed with `--no-deps -- -D warnings`. `cargo deny --locked check`, locked workspace and fuzz metadata, and source gates passed.
- Native Android arm64 libraries compiled through Gradle. `python3 scripts/ci/verify_native_elfs.py --lib-dir core/engine/build/generated/jniLibs --abis arm64-v8a` passed.
- Independent general, async cancellation, and unsafe-code reviews found no remaining actionable defects after fixes. Miri was not run locally.
- `integration.patch` was applied to the checksum-verified upstream package in a temporary directory; all resulting source files matched the vendored tree.
- The chain validation tests reproduced an untyped early rejection. The fix returns `RelayConfigRejected` from the single credential check; the regression assertions remain unchanged.

All compiler-backed local commands used the machine build gate. Rust commands ran from `native/rust` to select the pinned toolchain.

The successful import test now uses a valid 32-byte SS2022 key instead of an arbitrary password. All activation assertions are retained.

The final Kotlin gate passed with `build-gate -- env -u CARGO_BUILD_JOBS ./gradlew :core:data:runtime-state:testDebugUnitTest :core:service:testDebugUnitTest :app:testGithubFullDebugUnitTest staticAnalysis --max-workers=3 -Pripdpi.nativeCpuBudget=3 -Pripdpi.skipNativeBuild=true --no-daemon`. Native compilation and ELF verification passed separately before this repeat.

After rebase onto `2cf13604ef0ac2ba311f0e8a06085abb0134a7a4`, the 282 Shadowsocks/relay/fixture tests and 78 HTTP tests passed. Architecture health, locked metadata, and `just ci-gates` passed on the combined tree. Main integration used fast-forward; push succeeded.

The hosted `cargo fmt --manifest-path native/rust/Cargo.toml --all --check` also checks path dependencies. It found 30 upstream files that used different formatting. Repository formatting was applied without logic changes. The integration patch was regenerated and reproduced all 65 files exactly. All eleven Rust boundary guards passed.


## Combined-tree evidence and hosted blockers

- Implementation: `fe038f283d6a07c4e9a1aa7b14b3602670f7cee6`. Vendor formatting correction: `da1d0051866644cc29777c55bef5cb0701fad647`. Both reached main and origin.
- The parallel DNS integration on `1a1a1698e2f64f0a5cb67616d59414b7d6a3b92a` passed 7614 Kotlin tests, R8, staticAnalysis and source gates. Its completed Gradle log was inspected. The later settings integration retained identical native inputs.
- [CI 42194843280](https://github.com/po4yka/RIPDPI/actions/runs/42194843280) passed full Rust lint, workspace test shards, Miri, native packaging and relay interoperability before a later push cancelled the run. The initial plugin-download failure did not recur; native x86_64 passed. CodeQL passed. These results do not mean the full workflow passed.
- [CI 42198808996](https://github.com/po4yka/RIPDPI/actions/runs/42198808996), on the exact source SHA above, reached terminal failure. Static analysis, Kotlin coverage, Roborazzi, native packaging, JNI checks and all six release verification variants passed.
- Debug and release instrumentation compilation failed at `NativeDoqQuicClientInstrumentedTest.kt:30`: an unchanged call passes an Int where the new DNS codec requires a ByteArray. This call is outside the Shadowsocks migration.
- All three debug APK size checks failed. The gate tracks only `libripdpi.so` and `libripdpi-tunnel.so`; it does not measure every packaged native library.

## Native size attribution

The native verifier downloaded the pre-migration artifacts from [CI 42181223749](https://github.com/po4yka/RIPDPI/actions/runs/42181223749), source `4a346b9e50e53b7311ef1071d97b976ae4ad889c`, and compared them with CI 42194843280. Values are bytes.

| Tracked output | Before | After | Allowed |
|---|---:|---:|---:|
| arm64-v8a / libripdpi.so | 12136264 | 12138200 | 12124600 |
| x86 / libripdpi.so | 11869012 | 11870796 | 11831760 |
| x86_64 / libripdpi.so | 13597304 | 13600616 | 13559560 |
| All tracked libraries and ABIs | 80207960 | 80223672 | 80044100 |

Running the current size verifier on the old artifacts returned exit 1 with the same three ABI failures and total failure. The old total already exceeded the limit by 163860 bytes; the current excess is 179572 bytes. The measured increase across tracked outputs is 15712 bytes. The earlier successful CI had skipped the debug APK size job.

The separately measured x86_64 relay library grew by 37264 bytes; the size gate does not cover it. Published cargo-bloat reports for the two tracked packages were unchanged. No size threshold, baseline, protocol feature or safety check was changed to hide the existing overrun.

The task remains in review. The DNS instrumentation call and native budget require separate resolution before full hosted acceptance and archival.
