---
task_id: ENG-1794722736729815
change: dpi38-failure-contracts
commit_sha: 9c049bb49aba2ed7d0769b503ef72428679aca48
local: passed
local_evidence: All local gates passed on 9c049bb49aba2ed7d0769b503ef72428679aca48; 1402 unit tests, doctests, 20 local contracts, strict Clippy, workspace check and exact ci-gates. See final-gates-receipt.json in the audit log directory.
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

Component logs below record their original pre-rebase runs. The first commit in each row identifies the current rebased fix. The full final gate passed on the exact commit in the frontmatter. External compatibility and device acceptance remain unverified.

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI38-F1 | ENG-1794722945021715 | Commit `ec504da447d757ed341b1d096bc2a1bd624320b6`; interleaved TLS/QUIC and real emitted TLS continuation RED reproduced. Full seven-package lib suites passed 840 tests, plus 8 local probe contracts; strict all-target Clippy, formatter and normal hooks passed. Neutral/NoData, receipt ordering, duplicate/foreign/reset/expiry, same-context concurrency and UDP wire controls use real service ports. Receipt: `f1-ripdpi-receipts.json`. Companion `7fe1ab9237` corrects an existing timestamp assertion without changing pacer logic. Root merge retains three F2 route-budget initializers. | Passed |
| REQ-DPI38-F2 | ENG-1794722951308143 | Commit `49418e02055350ed94c2a05fa8c2f04b75a8af5f`; real accepted-connection counts at caps 0, 1 and 8; full runtime 434, adapter 79 and policy 95 unit tests passed; final three budget tests and all-target Clippy passed with normal hooks. Logs: `DPI-38-fix-logs/F2-green-corrected-fixture.log`, `F2-final-checks.log` in the audit output directory. | Passed |
| REQ-DPI38-F3 | ENG-1794722957091594 | Commit `8784ef56679c0fbc534d48273fc0c1ad0453a3a1`; real rustls TLS 1.2 and TLS 1.3 origins rejected the old emitter in RED and accepted the current builder with SNI in GREEN. Full reprobe 18, runtime 435 and adapter 79 unit tests passed; doctests, all-target Clippy, formatter and normal hooks passed. Logs: `F3-red-reprobe.log`, `F3-green-reprobe.log`, `F3-gates-resume.log`, `F3-commit.log`. | Passed |
| REQ-DPI38-F4 | ENG-1794722960734723 | Atomic root commit `a2887046e873b9a723dadf596d25cf33a2408e28`; the three approved components retain complete bounded HTTP evidence. Real pending-client RST, leading-line HEAD and CONNECT, and partial method at 75 ms reproduced in RED. Full root suites passed 1079 tests, with no failed, ignored or filtered tests; strict all-target Clippy, formatter and normal hooks passed. Log: `F4-integration-checks.log`. All 16 changed files match the approved worker tree. | Passed |
| REQ-DPI38-F5 | ENG-1794722964687558 | Commit `fc74873539ca6e574a07fce4c87033887866bc32`; real rustls TLS 1.2 warning continuation and raw record split regressions reproduced in RED. Component suites passed 1042 unit tests. Root integration passed all 1374 tests across ten affected crates, with no ignored or filtered tests; strict runtime all-target Clippy, formatter and normal hooks passed. The owning generator produced the intended packet and classifier API entries with locked rustdoc. Log: `F5-integration-final-default.log`. Test-only companion `87e06cf5a7` reserves both address families after a real ephemeral-port collision; both permit assertions remain. | Passed |
| REQ-DPI38-F6 | ENG-1794722968859091 | Commit `f0edd9550abecc44158f8ec1698a3daeffd29296`; malformed raw alert and policy acceptance RED reproduced. Classifier 108, packets 332 unit plus 5 integration, and policy 94 tests passed; all-target Clippy, formatter and normal hooks passed. The owning generator added only the intended packet-helper API entry. Logs: `f6-red-scoped-default.log`, `f6-policy-red.log`, `f6-cohesive-green.log`, `f6-cohesive-clippy.log`, `F6-api.log`, `F6-integration-commit.log`. | Passed |
| REQ-DPI38-F7 | ENG-1794722971027950 | Commit `015f68b6226c20c819b05c26ef229091606b37f8`; controlled-time RED and GREEN at TTL minus one and exact TTL; full strategy suites: 133 unit and 8 integration tests; all-target Clippy and normal hooks passed. Logs: `f7-red-default.log`, `f7-green-resume.log`, `f7-clippy.log`. | Passed |
| REQ-DPI38-F8 | ENG-1794722972628499 | Commit `2724b459014672644c75d65354c1a7a5d23dc700`; blocking timeout and nonblocking controls reproduced before repair; full reprobe suite: 17 tests; all-target runtime Clippy and normal hooks passed. Logs: `F8-red-default.log`, `F8-green.log`, `F8-clippy.log`, `F8-commit-retry.log`. | Passed |
| REQ-DPI38-F9 | ENG-1794722974631962 | Commit `1ebd62e9dba983f57132426ebc2151293fa97610`; four behavioral RED checks cover metric, terminal route cache, pending rotation and retained prior failure. After integration with F2/F3/F6/F8, full unit suites passed: classifier 108, packets 332, runtime 443, runtime adapter 79, desync adapter 56 and policy 96 (1114 total; none ignored or filtered). API generation used locked rustdoc and the owning snapshot code; normal hooks passed. Logs: `f9-closure-red.log`, `F9-api.log`, `F9-integration-checks.log`. F1 exact-token acceptance is covered by REQ-DPI38-F1 and the final suite. | Passed |
| REQ-DPI38-F10 | ENG-1794741735765085 | Commit `60396fa25de6aaee74fb3cf6a7b4aeff5d986acd`; a real client RST after accepted HTTP and a second request produced false `tcp_reset` confirmation while the origin stayed open (RED). After retirement before local shutdown, full runtime 455 tests passed, including local RST, runtime stop, genuine origin FIN/RST and reply after normal FIN; strict Clippy, formatter and normal hooks passed. Logs: `f10-red2.log`, `f10-final-receipts.json`, `f10-final-runtime-lib.log`. | Passed |
| REQ-DPI38-SCOPE | ENG-1794722976807450 | Read-only review approved exact 9c049bb49aba2ed7d0769b503ef72428679aca48. All 1402 unit tests, doctests, 8 probe contracts, 1 UDP ownership test and 11 UDP rebind tests passed; strict all-target Clippy, full workspace check, locked metadata, architecture health, exact ci-gates and task validation passed. Main integration and push remain pending. Receipt `final-gates-receipt.json`. | Pending publication |
