---
task_id: RST-1794557083402232
change: dpi11-random-record-contracts
commit_sha: 54660c4e1c671f94ae473f36ee060290745e93c2
local: passed
local_evidence: "369 owning tests passed with no ignored or filtered tests; clippy all targets with -D warnings, just ci-gates, architecture, locked metadata, and formatting passed. Independent pr-review and autoreview are clean. The user authorized rly14 validation for this task; the validation lease has been released."
remote_ci: not_applicable
remote_ci_evidence: Local regression acceptance owns this change; no hosted run is required.
device: not_applicable
device_evidence: Pure native host regression coverage; no Android, 32-bit device, or external transport acceptance claim.
artifact: not_applicable
artifact_evidence: No packaged native or Android artifact is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TLS-RANDREC-CONFIG | RST-1794557198598522 | cargo test --locked --jobs 4 -p ripdpi-desync -p ripdpi-proxy-config; primary/rotation JSON boundary tests | Passed |
| REQ-TLS-PRELUDE-ENVELOPE | RST-1794557200052250 | Same full suite; real/fake malformed envelopes with minor overrides and independent payload reconstruction | Passed |
| REQ-TLS-RANDREC-ARITHMETIC | RST-1794557201361703 | Same host suite; max_size=usize::MAX regression retains 3 nonempty fragments totaling 6 bytes | Passed |
| REQ-TLS-RANDREC-ADAPTIVE | RST-1794557202779697 | Same full suite; 768 feasible seed/profile outputs plus infeasible no-op cases | Passed |

## Combined acceptance

RST-1794557203876216:

- Full owning Cargo suites: 369 passed, 0 failed, 0 ignored, 0 filtered out.
- cargo clippy --locked --jobs 4 --all-targets -p ripdpi-desync -p ripdpi-proxy-config -- -D warnings: passed.
- cargo fmt for both affected crates with --check: passed.
- just ci-gates: passed, including all 13 Rust API snapshots.
- python3 scripts/ci/check_architecture_health.py: passed.
- cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1: passed.
- Independent pr-review: all four patches clean after the TLS minor rewrite guard was added.
- Structured autoreview: exit 0, no accepted/actionable findings; secret scan clean.
- Applicable implementation hooks passed before rebase; full validation passed again after rebase. No gates, baselines, manifests, or lockfiles were changed.

## Regression evidence

Before each repair, existing code failed the new regression: invalid native values were accepted; a 0x17 record was reframed; large partition bounds panicked; adaptive profiles emitted a 16-byte fragment below a saved minimum of 24 or accepted an infeasible saved interval. The final full suite includes each regression. The additional TLS minor case was confirmed in source review and is covered by the final passing suite.

Host test logs and structured review outputs are under /tmp/dpi11-*. They are local evidence, not hosted CI or device receipts. The exact commit above contains the validated implementation tree.

## Scope

This change does not expand multi-record ClientHello support, RNG distribution, default applicability, external peer coverage, or Android transport acceptance. These were separate audit limitations, not confirmed defects covered by this repair.

## Integration verification

The implementation was rebased on origin/main at 5007962b8a9bb9dd65c4e512f0d22be360af2491. The four repair patches are unchanged. Shared config inputs changed, so all owning tests, clippy, source gates, architecture, locked metadata, and formatting were checked again. The final total is 369 passing tests. Targeted review confirmed that upstream FakeRst root-mode changes do not affect TLSrandrec parsing, rotation, or its non-root tier. The existing capability Purpose is retained.
