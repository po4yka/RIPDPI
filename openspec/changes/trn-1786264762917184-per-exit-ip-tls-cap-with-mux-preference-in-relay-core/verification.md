---
task_id: TRN-1786264762917184
change: trn-1786264762917184-per-exit-ip-tls-cap-with-mux-preference-in-relay-core
commit_sha: 5d628726be70d32b699cc60657d67a9575fd9974
local: passed
local_evidence: "Observed RED before implementation: protocols::vless::tests::port_443_carrier_slots_enforce_cap_and_release failed with 'port 443 must reserve a physical-carrier slot'. Final local gates passed: cargo nextest run --locked -p ripdpi-relay-core -p ripdpi-relay-mux reported 247 tests run, 247 passed, 1 skipped; cargo nextest run --locked -p ripdpi-relay-core -p ripdpi-relay-mux -p ripdpi-session-limit -p ripdpi-proxy-runtime -p ripdpi-vless reported 647 tests run, 647 passed, 10 skipped; cargo clippy --locked for affected crates passed with -D warnings; cargo fmt --all -- --check passed; cargo metadata --manifest-path native/rust/Cargo.toml --locked passed; python3 scripts/ci/check_architecture_health.py passed with Current 23, Baseline 23, New 0, Worsened 0, Stale 0; native architecture contracts passed with 0 violations; pr-reviewer and async cancel-safety reviewer passes recorded locally."
remote_ci: required
remote_ci_evidence: "Not monitored per owner instruction on 2026-08-30: use local checks and push without tracking GitHub CI/CD state."
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
| REQ-TRN-1786264762917184-001 | TRN-1786264762919669 | `VlessRealityCarrierLimiter` enforces the default cap of 8 only for resolved port-443 VLESS+Reality carriers; non-mux direct relay and chain-entry paths resolve once and dial through the limiter; tests cover cap, release, and non-443 bypass. | passed |
| REQ-TRN-1786264762917184-002 | TRN-1786264762919309 | `vless_mux_streams_share_single_carrier` opens nine concurrent logical streams through `RelayMux::open_stream` and asserts the local VLESS fixture accepted exactly one physical carrier. | passed |
| REQ-TRN-1786264762917184-003 | TRN-1786264762919414 | `ripdpi-session-limit` owns the shared accounting primitive; `proxy-runtime` and `ripdpi-vless` each construct independent limiter instances, with tests covering shared-clone budgets without cross-subsystem double counting. | passed |
| REQ-TRN-1786264762917184-004 | TRN-1786264762919606 | Required nextest suites, affected clippy with `-D warnings`, fmt, metadata, architecture health/contracts, implementation review, and async cancel-safety review all passed locally; GitHub CI/CD was not monitored per owner instruction. | passed |
