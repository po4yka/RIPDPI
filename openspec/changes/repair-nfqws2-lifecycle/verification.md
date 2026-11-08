---
task_id: VPN-1794158885036621
change: repair-nfqws2-lifecycle
commit_sha: d57570e5cb8736dcee59cfc9ee918b3be8a5e937
local: passed
local_evidence: All 2098 service tests, 103 native tests, staticAnalysis, ci-gates, commit hooks, and independent review passed on the combined source tree.
remote_ci: required
remote_ci_evidence: Hosted CI has not run for the repairs.
device: blocked
device_evidence: Root activation, physical traffic, and external probes require separate authorization; no such evidence is claimed.
artifact: passed
artifact_evidence: The ARM64 Android root-helper debug executable compiled and its ELF was checked; this is not APK or release acceptance.
deployment: not_applicable
deployment_evidence: This repair does not deploy a service.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-NFQ-MONITOR | VPN-1794159017607315 | Dead-helper registration and stale-session regressions passed in the full service suite. | passed |
| REQ-NFQ-PROCESS | VPN-1794159020495755 | Scoped predecessor, uncertain ownership, bounded blocking IPC, and launcher exit regressions passed. | passed |
| REQ-NFQ-CHAINS | VPN-1794159023606659 | Foreign UID, delimited name, and proven legacy recovery regressions passed in the native suite. | passed |
| REQ-NFQ-FILE | VPN-1794159025344157 | Plain/compressed Lua fallback and dangling/outside symlink regressions passed. | passed |
| REQ-NFQ-REAP | VPN-1794159031763874 | Discovery debt, partial cleanup retries, orphan confirmation, activation/shutdown gating, and strict Kotlin reconciliation regressions passed. | passed |
| REQ-NFQ-COMPAT | VPN-1794159033246872 | Existing root/YAML compatibility tests, full service tests, native tests, staticAnalysis, ci-gates, ARM64 compile, and independent review passed. | passed |

## Acceptance scope

The user authorized local repairs, separate finding commits, main integration, and push. Existing device and external compatibility gaps remain separate. Do not archive while required or blocked evidence remains unresolved.

## Observed repair checks

- The final Rust source passed `cargo test --locked -p ripdpi-root-helper -p ripdpi-root-helper-protocol --jobs 4` from `native/rust` on Rust 1.98.1: 60 helper, 40 protocol, and 3 descriptor integration tests, with no failures or ignored tests. Final rebased-tree log: `/tmp/vpn10-final-rust-tests.log`.
- The final combined Kotlin source passed all 2098 service tests (276 XML reports, zero failures, errors, or skips). This includes the 38 focused lifecycle, endpoint, deadline, and reconciliation regressions. The final full Gradle invocation restored service test results FROM-CACHE after the same source passed execution; staticAnalysis passed. Log: `/tmp/vpn10-final-gradle.log` (BUILD SUCCESSFUL, 3 minutes 16 seconds).
- F1 was reproduced by the audit test while its owning source was unchanged. The F4 current function matched the audit oracle and still generated three foreign-UID deletion commands before the fix. F5 reproduced the outside compressed fallback. F2 reproduced lost retries after unlock; the new Kotlin caller regression failed before its fix.
- F3's first 36-test run found two incomplete positive fake-endpoint fixtures. The fixtures now explicitly complete their synthetic endpoint cleanup. Production guards and negative assertions were not reduced. The later 36-test and final 38-test runs passed.
- The first F4 host run used Rust 1.97.0 because it ran from the repository root. The later pinned 1.98.1 runs include every F4 regression; the final 103-test native result is the required compiler evidence.
- Independent read-only review covered every finding and the final Kotlin/Rust F2 contract. Review found and required real blocking IPC bounds, confirmed launcher exit, initial prerequisite discovery debt, and orphan exit confirmation. These findings were resolved before approval.
- `cargo metadata --locked --format-version 1` passed from `native/rust`: 112 workspace members. No Cargo manifest or lockfile change is part of this repair.

## Final combined gates and finding commits

- `build-gate --lane ripdpi -- env -u CARGO_BUILD_JOBS ./gradlew :core:service:testDebugUnitTest staticAnalysis -Pripdpi.skipNativeBuild=true --max-workers=4 -Pripdpi.nativeCpuBudget=4`: exit 0. Native compilation is a separate check below.
- `build-gate --lane ripdpi -- env CARGO_BUILD_JOBS=4 PATH="/Users/npochaev/.cargo/bin:$PATH" just ci-gates`: exit 0; all 11 source gates passed. Log: `/tmp/vpn10-final-ci-gates.log`; repeated after the final integration rebase in `/tmp/vpn10-delivery-ci-gates.log`. Linux-owned runtime-platform API extraction is outside the macOS source-gate coverage.
- The first combined staticAnalysis run found eight detekt violations in the repairs. Structural refactoring fixed them. No baseline or suppression was added. Full service tests and staticAnalysis passed afterward.
- The branch includes the intervening TLS commits through `1d9c9a31848476a1989ca0804e545659c7d4602b`. Final native tests, source gates, and ARM64 compilation were repeated after that base change.
- Normal commit hooks passed for all five finding commits, including Rust clippy and format checks for the native fixes.
- The delivery branch was rebased onto the later `eb5902afd70535a93195cede8ee310501517b165` main. None of the tested Kotlin/Gradle/Android inputs, repair sources, or 29 Android build receipt inputs changed. Source gates were repeated for the combined tree. Earlier verified branch references were retained. Hosted CI and device evidence remain open; this task stays in review.

| Finding | Commit |
|---|---|
| F1 monitor registration | `eb8aa5010` |
| F2 persistent cleanup debt | `d57570e5c` |
| F3 scoped helper shutdown and bounded IPC | `6808d8e91` |
| F4 firewall chain ownership | `083d1b8a9` |
| F5 actual Lua fallback validation | `f9543688c` |

## Android compile and artifact evidence

`build-gate --lane ripdpi -- env -u CARGO_BUILD_JOBS RUSTUP_TOOLCHAIN=1.98.1 ./gradlew :core:engine:buildRustRootHelper -Pripdpi.nativeAbisOverride=arm64-v8a --max-workers=4 -Pripdpi.nativeCpuBudget=4` passed on the final combined source in 1 minute 9 seconds. The initial attempt was rejected before compilation because the Gradle policy forbids ambient CARGO_BUILD_JOBS; the successful command lets the plugin set child job limits.

The debug arm64 executable is AArch64 PIE with `/system/bin/linker64`, three LOAD segments aligned to 0x4000, and only libc.so/libdl.so dynamic dependencies. SHA256: `4d27bac349653e69bedf373eee812ffc5be5501a029b2a3c5b3099b1e31544b7`. Build ID: `662c56387b16572f6d5ab3f4e9d86b91a198a54c`. All 29 input hashes (root-helper, transitive TLS profile code, Cargo files, toolchain, and config) were unchanged across the build. Logs: `/tmp/vpn10-final-android-helper-build.log`, `/tmp/vpn10-final-android-helper-elf.txt`, and `/tmp/vpn10-final-android-helper-source-hashes.json`.

This proves one Android target compiles. It does not prove an APK, release size, other ABIs, root activation, SELinux transfer, actual NFQUEUE delivery, or external compatibility. No physical device, root activation, or external traffic probe ran. Unproven legacy chains remain unchanged rather than risking another owner's rules; PID identity checks do not claim an atomic pidfd guarantee.
