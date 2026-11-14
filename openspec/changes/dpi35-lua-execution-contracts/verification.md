---
task_id: RST-1794684393149595
change: dpi35-lua-execution-contracts
commit_sha: 8357f0fc515aeae7723f8df1c2640a2fe6b96d89
local: passed
local_evidence: 1144 unique Rust tests, nine selected Kotlin tests, five-package all-targets Clippy, full staticAnalysis, ci-gates and independent reviews passed. See commands and limits below.
remote_ci: required
remote_ci_evidence: Hosted CI will be checked after the authorized push; no remote pass is claimed.
device: required
device_evidence: Android root/raw acceptance needs a separately authorized compatible target. No device or root activation ran in this repair.
artifact: not_applicable
artifact_evidence: No release artifact is produced. Kotlin unit tests use the standard native-less test configuration.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LUA-TEXT | RST-1794684567619271 | Trusted valid-bytecode rejection covers six external entry points; text and bundled script tests pass. | passed |
| REQ-LUA-MODIFY | RST-1794684588025982 | Five packet_modify cases and three packet-byte cases pass. Exact UDP packets match upstream vectors; TCP MODIFY preserves length/sequence space. Modifier-only TTL plans are covered. | passed |
| REQ-LUA-FLOW | RST-1794684595798085 | More than 3072 bypassed IDs do not restart admitted state or activate a bypassed flow later. The real proxy sender regression passes. | passed |
| REQ-LUA-DEPENDENCIES | RST-1794684604990553 | UI and jail-path tests plus a fresh socket registry verify ordered bundled dependencies and exact HTTP bytes. | passed |
| REQ-LUA-ACTIVATION | RST-1794684608788278 | Invalid syntax, file, function, path and YAML return construction errors across on_fail policies. The service root-owner extraction test passes. | passed |
| REQ-LUA-CLOCK | RST-1794684611101060 | The real VM returns two integers with seconds bracketed by the host clock and valid subsecond nanoseconds. | passed |

## Observed checks

The six repair commits end at `commit_sha`. This later evidence commit changes documentation and task state only. All compiler-backed commands used `build-gate --lane ripdpi`. Cargo used the pinned Rust 1.100.0 toolchain, `--locked`, at most four jobs and one worktree-local target. Local debug information was disabled to reduce disk use; assertions and overflow checks stayed enabled.

| Check | Result | Evidence |
|---|---|---|
| `cargo test --locked --jobs 4 -p ripdpi-strategy-lua -p ripdpi-strategy-registry --features ripdpi-strategy-registry/lua-strategies -p ripdpi-tunnel-intercept -p ripdpi-tunnel-core` | Retained Lua portion: 63 passed, zero failed/ignored. The first registry integration run exposed an old empty-action expectation for MODIFY. The owning assertion was corrected to require Write(payload), while still excluding later Split. | `/tmp/dpi35-combined-rust-tests.log` |
| `cargo test --locked --jobs 4 -p ripdpi-strategy-registry --features lua-strategies` | 52 passed, zero failed/ignored after the assertion correction. The final commit check repeats only this package after the formatter-only assertion change. | `/tmp/dpi35-combined-registry-tests.log`; `/tmp/dpi35-registry-final-commit.log` |
| `cargo test --locked --jobs 4 -p ripdpi-tunnel-intercept -p ripdpi-tunnel-core` | 607 passed, zero failed/ignored. | `/tmp/dpi35-combined-tunnel-tests.log` |
| `cargo test --locked --jobs 4 -p ripdpi-proxy-runtime --lib` | 422 passed, zero failed/ignored, including the real sender lifetime regression. | `/tmp/dpi35-combined-proxy-tests.log` |
| `cargo clippy --locked --jobs 4 -p ripdpi-strategy-lua -p ripdpi-strategy-registry -p ripdpi-tunnel-intercept -p ripdpi-tunnel-core -p ripdpi-proxy-runtime --all-targets -- -D warnings` | Exit 0. Existing Cargo manifest unused-dependency notices remain informational. | `/tmp/dpi35-combined-clippy.log` |
| `./gradlew -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=4 --max-workers=4 :core:service:testDebugUnitTest --tests '*LuaScriptJailPathsTest' --tests '*LuaConnectionPolicyResolverTest' :app:testGithubFullDebugUnitTest --tests '*StrategyConfigRouteTest' staticAnalysis` | Exit 0; nine tests, zero failures/errors/skipped. Full staticAnalysis passed. Local heap caps: Gradle 5 GiB, Kotlin 3 GiB. | `/tmp/dpi35-kotlin-checks.log`; module JUnit XML results |
| `just ci-gates` | Exit 0; architecture, source budgets, contracts, locale/translation and API snapshot gates passed. No baseline was extended. | `/tmp/dpi35-ci-gates-final.log` |
| `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1 --no-deps` | Exit 0; 113 workspace members. | `/tmp/dpi35-cargo-metadata.json` |
| Independent read-only review | No remaining actionable findings. The initial modifier-only F2 concern was repaired and re-reviewed. | Separate implementation and review agents; no reviewer source writes |
| Normal commit hooks | All six code commits passed their normal hooks. An initial intent-to-add stash failure was resolved by removing only this task's intent entries; a formatting failure was fixed in the owning assertion. No gate was disabled. | `/tmp/dpi35-commit-f1.log` through finding-specific commit logs |

The total is 1144 unique Rust tests, not a sum that counts repeated registry tests twice. The first three implementation worktrees also recorded red regressions and narrow green suites before integration.

## Limits

- No emulator, physical device, NFQUEUE, privileged raw emitter or external server was activated.
- TUN packet bytes and activation error propagation are verified locally. Real TUN-to-underlay sequence/ACK and routing acceptance remain separate from this host proof.
- Arbitrary C stdlib duration, hard cancellation, profile-wide RSS, concurrent jail-file replacement and zeroization hypotheses from the audit are not asserted as solved defects.
- The separate trusted ABI6 nfqws2 backend, dependencies, JNI signatures, schemas, locales and baselines are unchanged.
- Admission closes for new Lua flows after one engine reaches 1024 admitted states. It stays closed until engine replacement, including after a slot closes. Existing admitted flows retain state.
- Remote CI and device categories remain required; this change is not archived and does not claim full upstream or Android raw compatibility.
