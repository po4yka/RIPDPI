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

## Hosted CI follow-up

The original failure was CI run 42839708524, attempt 1, cargo-deny job
129911598962 at commit 71a4c30824c79f6d4c4e886ac2c0903eff9c0529.
The waiver validator rejected RUSTSEC-2024-0436 on its 2026-11-10 expiry.

The first implementation run,
<https://github.com/po4yka/RIPDPI/actions/runs/42855910944>, passed cargo-deny
without the removed waiver. It then exposed two CI failures that the expired
waiver had blocked behind preflight:

- `rust-fuzz-smoke` job 129969888884 rejected the separate fuzz lockfile
  under `--locked`. Local locked metadata reproduced the error. Cargo metadata
  removed only the stale `ripdpi-xhttp -> uuid` dependency edge; no package
  versions changed. Full locked metadata and
  `cargo +nightly test --locked --jobs 4 --test tls_record_inputs` then passed
  (five tests).
- `rust-lint` job 129969888728 rejected an unchanged raw-slice call because
  its existing allowlist entry named line 383 instead of line 386. The entry
  now points to the actual line. Its scope, rationale, and review date are
  unchanged. The raw-slice scanner and all 28 tests in
  `test_check_soundness_35_49.py` passed.

Independent read-only review confirmed both repairs. They preserve the root
workspace build graph. Run
<https://github.com/po4yka/RIPDPI/actions/runs/42857564018> passed cargo-deny,
rust-lint, and rust-fuzz-smoke, then exposed the fixture issue below.

## Independent TLS fixture subjects

Run 42857564018, attempt 1, workspace shard job 129983096648 failed the
MASQUE and Trojan tests that trust the second PEM bundle root. Both failed
three times on Linux with a self-signed certificate verification error.
The macOS suite passed the original tests.

The unrelated and trusted self-signed fixture certificates had identical
Subject DNs and distinct keys. BoringSSL can select the first issuer with
that subject; equal-subject sorting does not define a portable order.
The production code already parses and adds every PEM certificate.

The two fixture generators now add an OU containing the SHA-256 fingerprint
of the public key. CN and SAN values stay valid for their existing hostnames.
The existing TLS tests assert distinct fixture subjects before the real
handshake and echo exchange. Both new preconditions failed locally before
the generator change and passed after it. No production TLS verifier changed.

After this repair, all 7138 workspace tests and the startup smoke passed
again, as did workspace clippy, CI source gates, and GithubFullDebug Android
assembly for arm64-v8a. Independent code and cancel-safety reviews found no
remaining issue. Hosted verification of this fixture repair is pending.
