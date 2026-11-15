---
task_id: RST-1794722628748844
change: dpi37-privileged-safety
commit_sha: cf2a1337821f3b45cf2bfec6066de4e52ace75aa
local: blocked
local_evidence: "Combined source host tests 181 passed, strict Clippy passed, Android aarch64 test compilation passed, ci-gates and locked metadata passed. Full Linux tests had 349 passed and four failures. Three BPF attach tests returned EPERM before and after the fixes. One UDP port rebind fixture failed once, then the full unchanged Linux helper suite passed 72 of 72. The full Linux suite is not green."
remote_ci: required
remote_ci_evidence: "Hosted CI for the requested main push remains unverified."
device: not_applicable
device_evidence: "No root activation or physical-device acceptance was authorized for this code repair."
artifact: not_applicable
artifact_evidence: "No APK or release artifact is produced."
deployment: not_applicable
deployment_evidence: "No deployment is owned by this change."
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PRIV-REPLY | RST-1794722773040555 | Real Unix-socket typed maximum high-byte ICMP response, bounded metadata, legacy-peer refusal, frame cap, descriptor lifetime and deadline tests | passed |
| REQ-PRIV-CHECKSUM | RST-1794722775543185 | Independent fixed odd and even ICMPv4 messages through the production parser; corrupt header and payload bytes are refused | passed |
| REQ-PRIV-RESTORE | RST-1794722777883000 | Both production early-error send paths preserve the original error and failed restoration; a loopback peer receives no payload | passed |
| REQ-PRIV-REPLY | RST-1794722780591649 | Combined owning tests, strict Clippy, Android compilation, source gates and independent reviews | partial; Linux BPF environment and hosted CI remain open |

## Observed checks

The source SHA above includes the three fix commits after a rebase on origin/main.
Cargo commands use the repository guard, locked resolution and at most four workers.
The four owning packages are ripdpi-privileged-ops, ripdpi-root-helper-protocol,
ripdpi-root-helper and ripdpi-runtime-platform.

- Frozen original-logic regression run on Linux reproduced all four new failures for F1, F2 and both F3 exits. The three existing BPF EPERM failures were also present.
- `cargo test --locked --jobs 4 --no-fail-fast -p ripdpi-privileged-ops -p ripdpi-root-helper-protocol -p ripdpi-root-helper -p ripdpi-runtime-platform` on macOS passed all 181 tests on the combined source.
- `cargo clippy --locked --jobs 4 -p ripdpi-privileged-ops -p ripdpi-root-helper-protocol -p ripdpi-root-helper -p ripdpi-runtime-platform --all-targets -- -D warnings` passed on the combined source.
- The same four Linux packages ran with `cargo test --locked --offline --jobs 4 --no-fail-fast` in the existing Rust 1.100.0 container. The container used uid 65534, no network, no capabilities, no-new-privileges and read-only source and dependency mounts. The full run had 349 passed and four failures. All four new regression checks passed.
- The Linux failures were the three BPF attach tests and `invalid_udp_split_reports_pre_send_failure_and_closes_descriptor`, which returned AddrInUse on the final port rebind. One full unchanged Linux helper retry passed 72 of 72 with no filters or skipped tests. A concurrent ephemeral-port reuse is a hypothesis, not a proven cause or a proven descriptor leak.
- A pre-rebase macOS helper run failed once at the nfqws2 undo fixture assertion. One unchanged full helper retry passed 71 of 71; the final combined host run also passed. A fixture timeout is only a hypothesis.
- `cargo check --locked --jobs 4 --tests --target aarch64-linux-android` for the four packages passed with NDK 30.0.16248370 and minSdk 27.
- Exact `just ci-gates` passed before and after the rebase. Rust API snapshots matched; the existing Darwin policy leaves the canonical runtime-platform snapshot to Linux.
- `cargo metadata --locked --manifest-path native/rust/Cargo.toml --format-version 1` passed. Dependencies, lockfiles, baselines and golden fixtures did not change.
- Independent pr-reviewer and unsafe-code-auditor found no actionable defects in the final change. The two compile/lint corrections were also reviewed. No new unsafe blocks or syscall ABI layouts were added.

## Evidence limits

The first repaired run found a missing test import and an equivalent-boolean Clippy warning.
Both were fixed. A later Android launcher was removed by external temporary-file cleanup;
that run exited 127 without invoking the compiler. The launcher was then kept in the job
worktree and compilation passed. Those failed attempts remain in the evidence record.

C establishes the code change. V covers local IPC bytes and bounds, production checksum
parsing and loopback error cleanup with test-only syscall faults. It does not cover actual
raw packet emission or a real TCP_REPAIR restoration failure. Compile-only Android checks
are not device execution. Root-only test early returns do not establish privileged V.
The checks add no claim of full specification coverage S or external interoperability E.

Actual root Linux/Android wire acceptance, capability denial and real repair restoration
acceptance require a separate authorized run. H1-H4 from the audit remain unconfirmed
boundary risks. No root activation, physical-device probe or external-network probe ran.
