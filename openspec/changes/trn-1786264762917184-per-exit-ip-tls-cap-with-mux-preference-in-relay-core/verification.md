---
task_id: TRN-1786264762917184
change: trn-1786264762917184-per-exit-ip-tls-cap-with-mux-preference-in-relay-core
commit_sha: 5d628726be70d32b699cc60657d67a9575fd9974
local: passed
local_evidence: "Observed RED before implementation: protocols::vless::tests::port_443_carrier_slots_enforce_cap_and_release failed with 'port 443 must reserve a physical-carrier slot'. Revalidated current main on 2026-08-31: cargo nextest run --locked -p ripdpi-relay-core -p ripdpi-relay-mux -p ripdpi-session-limit -p ripdpi-vless reported 368 tests run, 368 passed, 3 skipped; the broader affected-crate run passed 648 of 649 tests and hit one unrelated QUIC network-fixture timeout, whose isolated retry then passed 1 of 1. Cargo clippy --locked for affected crates passed with -D warnings; cargo fmt --all -- --check and locked cargo metadata passed; cargo deny passed with only accepted workspace warnings; architecture health passed with Current 23, Baseline 23, New 0, Worsened 0, Stale 0; 18 native architecture contract tests and taskctl validation passed. Read-only code-mapper and implementation-review subagents found no blocking source defect and confirmed the implementation is already present on main."
remote_ci: not_applicable
remote_ci_evidence: "The owner explicitly requested local verification and push without launching, waiting for, or monitoring GitHub CI/CD."
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
