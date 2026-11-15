---
task_id: ENG-1794722736729815
change: dpi38-failure-contracts
commit_sha: null
local: required
local_evidence: Pending regression tests, affected Rust suites, Clippy, workspace check and ci-gates.
remote_ci: not_applicable
remote_ci_evidence: Local source repair acceptance does not depend on a hosted runner; any push-triggered CI is reported separately.
device: not_applicable
device_evidence: These internal Rust behavior fixes use local socket and independent TLS oracles; physical root and external-network acceptance are outside the authorized test scope.
artifact: not_applicable
artifact_evidence: No release or packaged binary is delivered by this source change; native source compilation is part of local validation.
deployment: not_applicable
deployment_evidence: No production deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI38-F1 | ENG-1794722945021715 | Commit `374331b5e7`; interleaved TLS/QUIC and real emitted TLS continuation RED reproduced. Full seven-package lib suites passed 840 tests, plus 8 local probe contracts; strict all-target Clippy, formatter and normal hooks passed. Neutral/NoData, receipt ordering, duplicate/foreign/reset/expiry, same-context concurrency and UDP wire controls use real service ports. Receipt: `f1-ripdpi-receipts.json`. Companion `2f8b236aca` corrects an existing timestamp assertion without changing pacer logic. Root merge retains three F2 route-budget initializers. | Passed locally; combined UDP boundary and final gates pending |
| REQ-DPI38-F2 | ENG-1794722951308143 | Commit `65ed760370`; real accepted-connection counts at caps 0, 1 and 8; full runtime 434, adapter 79 and policy 95 unit tests passed; final three budget tests and all-target Clippy passed with normal hooks. Logs: `DPI-38-fix-logs/F2-green-corrected-fixture.log`, `F2-final-checks.log` in the audit output directory. | Passed locally; combined gate pending |
| REQ-DPI38-F3 | ENG-1794722957091594 | Commit `c513e53388`; real rustls TLS 1.2 and TLS 1.3 origins rejected the old emitter in RED and accepted the current builder with SNI in GREEN. Full reprobe 18, runtime 435 and adapter 79 unit tests passed; doctests, all-target Clippy, formatter and normal hooks passed. Logs: `F3-red-reprobe.log`, `F3-green-reprobe.log`, `F3-gates-resume.log`, `F3-commit.log`. | Passed locally; combined gate pending |
| REQ-DPI38-F4 | ENG-1794722960734723 | Atomic root commit `f78847a15acaefdf06d637eee0c8b94b00c17b14`; the three approved components retain complete bounded HTTP evidence. Real pending-client RST, leading-line HEAD and CONNECT, and partial method at 75 ms reproduced in RED. Full root suites passed 1079 tests, with no failed, ignored or filtered tests; strict all-target Clippy, formatter and normal hooks passed. Log: `F4-integration-checks.log`. All 16 changed files match the approved worker tree. | Passed locally; final rebased gates pending |
| REQ-DPI38-F5 | ENG-1794722964687558 | Commit `23e98d113b`; real rustls TLS 1.2 warning continuation and raw record split regressions reproduced in RED. Component suites passed 1042 unit tests. Root integration passed all 1374 tests across ten affected crates, with no ignored or filtered tests; strict runtime all-target Clippy, formatter and normal hooks passed. The owning generator produced the intended packet and classifier API entries with locked rustdoc. Log: `F5-integration-final-default.log`. Test-only companion `9b91fd5f89` reserves both address families after a real ephemeral-port collision; both permit assertions remain. | Passed locally; final rebased gates pending |
| REQ-DPI38-F6 | ENG-1794722968859091 | Commit `3082dab6d5`; malformed raw alert and policy acceptance RED reproduced. Classifier 108, packets 332 unit plus 5 integration, and policy 94 tests passed; all-target Clippy, formatter and normal hooks passed. The owning generator added only the intended packet-helper API entry. Logs: `f6-red-scoped-default.log`, `f6-policy-red.log`, `f6-cohesive-green.log`, `f6-cohesive-clippy.log`, `F6-api.log`, `F6-integration-commit.log`. | Passed locally; combined gate pending |
| REQ-DPI38-F7 | ENG-1794722971027950 | Commit `2558425f90`; controlled-time RED and GREEN at TTL minus one and exact TTL; full strategy suites: 133 unit and 8 integration tests; all-target Clippy and normal hooks passed. Logs: `f7-red-default.log`, `f7-green-resume.log`, `f7-clippy.log`. | Passed locally; combined gate pending |
| REQ-DPI38-F8 | ENG-1794722972628499 | Commit `af4a29f1e9`; blocking timeout and nonblocking controls reproduced before repair; full reprobe suite: 17 tests; all-target runtime Clippy and normal hooks passed. Logs: `F8-red-default.log`, `F8-green.log`, `F8-clippy.log`, `F8-commit-retry.log`. | Passed locally; combined gate pending |
| REQ-DPI38-F9 | ENG-1794722974631962 | Commit `ce0c6b6349`; four behavioral RED checks cover metric, terminal route cache, pending rotation and retained prior failure. After integration with F2/F3/F6/F8, full unit suites passed: classifier 108, packets 332, runtime 443, runtime adapter 79, desync adapter 56 and policy 96 (1114 total; none ignored or filtered). API generation used locked rustdoc and the owning snapshot code; normal hooks passed. Logs: `f9-closure-red.log`, `F9-api.log`, `F9-integration-checks.log`. F1 exact-token acceptance remains a separate step. | Passed locally; combined gate pending |
| REQ-DPI38-F10 | ENG-1794741735765085 | Commit `29aab96d9b`; a real client RST after accepted HTTP and a second request produced false `tcp_reset` confirmation while the origin stayed open (RED). After retirement before local shutdown, full runtime 455 tests passed, including local RST, runtime stop, genuine origin FIN/RST and reply after normal FIN; strict Clippy, formatter and normal hooks passed. Logs: `f10-red2.log`, `f10-final-receipts.json`, `f10-final-runtime-lib.log`. | Passed locally; F4 pending-owner integration and final gates pending |
| REQ-DPI38-SCOPE | ENG-1794722976807450 | Review, workspace check, cargo metadata --locked, ci-gates and final diff | Pending |
