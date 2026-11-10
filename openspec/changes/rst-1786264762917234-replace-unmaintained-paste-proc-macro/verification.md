---
task_id: RST-1786264762917234
change: rst-1786264762917234-replace-unmaintained-paste-proc-macro
commit_sha: null
local: passed
local_evidence: On 2026-11-10 the adapter tests, workspace suite (7138 passed), startup smoke, workspace clippy, formatting, CI source gates, cargo-deny 0.19.8, waiver validator, and arm64-v8a GithubFullDebug APK build passed. See local verification below.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: No Android device behavior is owned by this portfolio area.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this portfolio area.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RST-1786264762917234-001 | RST-1786264762919747 | Local checks below passed; exact-SHA hosted CI is pending. | required |

## Local verification on 2026-11-10

- `cargo metadata --locked --offline --format-version 1`: all-target graph
  contains only the local `paste` adapter, with `pastey` as its dependency.
  The adapter is a workspace member. No registry-sourced `paste` remains.
- The Cargo cache archive for `pastey` 0.2.3 matches the reviewed checksum in
  `native/rust/vendor/paste/README.md`. The lockfile has no unrelated changes.
- `cargo test --locked --jobs 4 -p paste`: three compatibility tests passed.
- `bash scripts/ci/run-rust-workspace-tests.sh`: architecture checks, AWG
  backend, smoltcp IPv4/IPv6 TCP, 7138 workspace tests, and the startup smoke
  passed. The normal CI filter skipped 65 tests. Nextest reported one leaky
  test: `ripdpi-tor::bootstrap_retry` cancellation regression; it returned
  success. The test starts a shell child that can outlive its cancelled waiter.
- `cargo clippy --locked --jobs 4 --workspace --all-targets -- -D warnings`
  and `cargo fmt --all --check`: passed.
- CI-pinned `cargo-deny` 0.19.8, with the repository policy and `--locked`:
  advisories, bans, licenses, and sources passed without RUSTSEC-2024-0436.
- `python3 scripts/ci/check_rust_advisory_waivers.py` and its two unit tests:
  passed. The remaining RSA waiver is unchanged.
- `just ci-gates`: passed. The Linux-owned runtime-platform API snapshot
  is excluded by the existing host policy on macOS.
- `./gradlew :app:assembleGithubFullDebug --max-workers=4
  -Pripdpi.nativeCpuBudget=4`: passed, arm64-v8a. The local build used the
  documented 5 GiB Gradle and 3 GiB Kotlin heap limits. No device run was requested.
- Independent read-only review found no adapter defects. Its stale task-status
  finding was corrected through `taskctl`.
