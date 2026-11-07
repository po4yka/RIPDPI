---
task_id: PRX-1794071497680636
change: fix-socks5-connect-contracts
commit_sha: 5f895598e682425ccdffca9b082951657590ea6c
local: passed
local_evidence: 300 runtime and 73 session unit tests, 39 network E2E tests, all 6 relay-lane tests, just ci-gates, and independent review passed.
remote_ci: not_applicable
remote_ci_evidence: Local gates are required; hosted CI execution is not claimed by this change.
device: not_applicable
device_evidence: Host-loopback wire contract corrections; no Android lifecycle changes or device acceptance claim.
artifact: not_applicable
artifact_evidence: No shipped binary or golden artifact is updated.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-S5-SUCCESS | PRX-1794071600786245 | delayed CONNECT loopback tests | passed |
| REQ-S5-HEADER | PRX-1794071601228418 | header parser and loopback tests | passed |
| REQ-S5-ATYP | PRX-1794071601781187 | unsupported ATYP wire test | passed |
| REQ-S5-POLICY | PRX-1794071602368425 | self-target and Block wire tests | passed |
| REQ-S5-AUTH | PRX-1794071602984411 | zero-length auth tests | passed |
| REQ-S5-COMPAT | PRX-1794071600786245 | affected crate tests and network E2E | passed |

## Observed local evidence

Source commit: `5f895598e682425ccdffca9b082951657590ea6c`.
Commands ran from the dedicated job worktree. Cargo used `CARGO_TARGET_DIR=/tmp/ripdpi-s5-policy-target`.

```sh
cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-session -p ripdpi-proxy-runtime --locked --lib
cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-proxy-runtime --locked --test network_e2e -- --test-threads=1
cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-proxy-runtime --locked --test network_e2e -- --ignored --test-threads=1
just ci-gates
python3 scripts/ci/check_architecture_health.py
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1
```

- Unit suites: 300 runtime + 73 session passed, no ignored tests.
- Network E2E: 39 passed; the six tests assigned to the relay lane were then run explicitly: six passed, none ignored. Total: 418 executed tests passed.
- Source gates: architecture health, file and hotspot limits, cross-language contracts, protocol fixtures, diagnostics boundaries, routes, locale parity/export, and API snapshots passed. No baselines were extended.
- Independent read-only review passed for all five defects and both follow-up fixes. Review caught payload-dependent TFO and hostname-dependent route preservation before commit.
- A full parallel unit run caught an auth reply reset. A bounded full-frame read fixed it without weakening the wire assertion. The explicit relay lane caught an extra provisional upstream connection. Known-route selection fixed it; the original exclusion assertion remains.
- All source commits passed normal commit hooks, including affected-crate Clippy and format checks.

## Limits

Host tests ran on macOS. The Linux/Android real TFO socket branch, physical-device acceptance, external compatibility, hosted CI, and deployment were not run or claimed. The macOS TFO test checks the actual platform Unsupported result. Audit hypotheses about slow DNS/SYN shutdown, chained BND semantics, and timeout/TTL classification remain hypotheses; this change fixes the five confirmed defects only.
