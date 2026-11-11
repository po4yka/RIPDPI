---
task_id: TRN-1794377577809835
change: yamux-stream-limits
commit_sha: 97e4af86a6f601cdb352b63cd3c782ba6f966430
local: passed
local_evidence: Rust 1.98.1 passed 191 library tests and 9 relay-core mux tests after rebase. Affected-crate Clippy, rustfmt, locked metadata, ci-gates and regenerated task validation passed.
remote_ci: not_applicable
remote_ci_evidence: This local input-validation repair does not require hosted execution; remote CI is not claimed.
device: not_applicable
device_evidence: The failure occurs in host-independent configuration arithmetic before carrier I/O. No Android behavior changes.
artifact: not_applicable
artifact_evidence: No packaged binary or generated output changes.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-YAMUX-LIMIT | TRN-1794377627725108 | Parser and direct-field boundaries plus real backend echo with limit 2048 | Passed |
| REQ-YAMUX-CONSTRUCTOR | TRN-1794377627725108 | Capacities 0, 4097 and usize::MAX fail before preamble; 4096 establishes | Passed |

## Observed checks

- RED: the old parser accepted 2049 and the direct constructor panicked at 4097. Two regression tests failed as expected.
- GREEN: `cargo test --locked -p ripdpi-vless --lib` passed 191 tests with none ignored or filtered.
- `cargo test --locked -p ripdpi-relay-core --lib mux` passed 9 tests. The maximum-limit case completed a real loopback REALITY/VLESS/yamux echo exchange and opened exactly one carrier.
- `cargo clippy --locked -p ripdpi-vless -p ripdpi-relay-core --all-targets --no-deps -- -D warnings` passed. Existing vendored h3 warnings remain outside these crates.
- `cargo fmt --all -- --check`, full `cargo metadata --locked --format-version 1`, and `just ci-gates` passed after rebase. The Linux-owned runtime-platform API snapshot is excluded on Darwin by the existing gate.
- The generated task board was refreshed after rebase; `taskctl validate` passed.
- Independent correctness and cancellation reviews found no confirmed defect.
- One intermediate compiler run failed while writing a missing Cargo fingerprint. The complete acceptance run used a separate worktree-local target directory and passed. No cache root cause is asserted.
- A temporary test placement exceeded the parent test file's LoC budget. The new test was moved to the existing backend fixture module; budgets and baselines were unchanged.
- Android and 32-bit execution were not run. Checked multiplication and fixed boundary tests establish host behavior; no device or external-server compatibility is claimed.
