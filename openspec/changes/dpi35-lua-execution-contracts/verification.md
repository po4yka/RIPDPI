---
task_id: RST-1794684393149595
change: dpi35-lua-execution-contracts
commit_sha: efadd22929fd1f5b6a0d4cdb7f2ff2af614a53e7
local: passed
local_evidence: 1146 unique Rust tests, nine selected Kotlin tests, five-package all-targets Clippy, full staticAnalysis, ci-gates and independent reviews passed. See commands and limits below.
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

## Observed checks on the rebased tree

The six repair commits end at `commit_sha`, after rebase on main `2c7ac9a54a90997fd571fd172feb0b42c1ad62ac`. The later evidence commits change documentation and task state only. The rebase preserved the upstream socket-window rejection before Lua owner filtering and both activation/window regression groups. One fallible constructor owns the behavior; the redundant try-constructor was removed and all callers were checked again.

All compiler-backed commands used `build-gate --lane ripdpi`, pinned Rust 1.100.0, at most four jobs, and one worktree-local Cargo target. Local debug information was disabled to reduce disk use; assertions and overflow checks stayed enabled. Gradle used four workers, a 5 GiB heap and a 3 GiB Kotlin heap.

| Check | Result | Evidence |
|---|---|---|
| `cargo test --locked --jobs 4 -p ripdpi-strategy-lua -p ripdpi-strategy-registry --features ripdpi-strategy-registry/lua-strategies -p ripdpi-tunnel-intercept -p ripdpi-tunnel-core` | 723 passed, zero failed/ignored. | `/tmp/dpi35-rebased-rust-tests.log` |
| `cargo test --locked --jobs 4 -p ripdpi-proxy-runtime --lib` | 423 passed, zero failed/ignored, including the real sender lifetime regression. | `/tmp/dpi35-rebased-proxy-tests.log` |
| `cargo clippy --locked --jobs 4 -p ripdpi-strategy-lua -p ripdpi-strategy-registry -p ripdpi-tunnel-intercept -p ripdpi-tunnel-core -p ripdpi-proxy-runtime --all-targets -- -D warnings` | Exit 0. Existing Cargo manifest unused-dependency notices remain informational. | `/tmp/dpi35-rebased-clippy.log` |
| `./gradlew -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=4 --max-workers=4 :core:service:testDebugUnitTest --tests '*LuaScriptJailPathsTest' --tests '*LuaConnectionPolicyResolverTest' :app:testGithubFullDebugUnitTest --tests '*StrategyConfigRouteTest' staticAnalysis` | Exit 0; nine tests, zero failures/errors/skipped. Full staticAnalysis passed. | `/tmp/dpi35-rebased-kotlin-checks.log`; module JUnit XML results |
| `just ci-gates` | Exit 0; architecture, source budgets, contracts, locale/translation and API snapshot gates passed. No baseline was extended. | `/tmp/dpi35-rebased-ci-gates.log` |
| `cargo metadata --manifest-path Cargo.toml --locked --format-version 1 --no-deps` from native/rust | Exit 0; 113 workspace members. | `/tmp/dpi35-rebased-metadata.json` |
| Independent read-only review after rebase | No actionable findings. The F5 conflict resolution retained upstream window admission and strict Lua activation errors. | Clean reviewed HEAD `f06a56333a95145394301c31b4c99036542e4b1b`; no reviewer writes |
| Normal commit hooks | All six original repair commits passed their normal hooks. No gate was disabled. Rebase conflict changes were then re-reviewed and compiler gates repeated. | `/tmp/dpi35-commit-f1.log` through finding-specific commit logs |

The total is 1146 unique Rust tests. Before rebase, 1144 unique Rust tests and the same nine Kotlin tests passed; the final run includes two new upstream admission regressions. Original implementation worktrees recorded red regressions before their narrow green suites. The initial registry empty-action assertion was corrected to require Write(payload), while still excluding later Split. Formatting was corrected before commit. Earlier logs remain separate from the rebased acceptance logs.

## Limits

- No emulator, physical device, NFQUEUE, privileged raw emitter or external server was activated.
- TUN packet bytes and activation error propagation are verified locally. Real TUN-to-underlay sequence/ACK and routing acceptance remain separate from this host proof.
- Arbitrary C stdlib duration, hard cancellation, profile-wide RSS, concurrent jail-file replacement and zeroization hypotheses from the audit are not asserted as solved defects.
- The separate trusted ABI6 nfqws2 backend, dependencies, JNI signatures, schemas, locales and baselines are unchanged.
- Admission closes for new Lua flows after one engine reaches 1024 admitted states. It stays closed until engine replacement, including after a slot closes. Existing admitted flows retain state.
- Remote CI and device categories remain required; this change is not archived and does not claim full upstream or Android raw compatibility.
