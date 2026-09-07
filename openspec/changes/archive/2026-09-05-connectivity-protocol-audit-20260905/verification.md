---
task_id: DGN-1788582590436769
change: connectivity-protocol-audit-20260905
commit_sha: a0986d2e7495c0cbefd6e47781f8b9e16cdaae5d
local: passed
local_evidence: Rebased native 1378 tests and workspace clippy passed; Python 645 plus CI 33 tests passed. Local Android dependency access failed; hosted CI supplies pinned Android evidence.
remote_ci: passed
remote_ci_evidence: https://github.com/po4yka/RIPDPI/actions/runs/33950376859 completed successfully on the exact application SHA; 5403 Rust and 9044 JVM executions passed, plus static analysis and release checks.
device: passed
device_evidence: CI passed API 27/33/35/36/37 instrumentation. API 35 required five JNI and two real Xray TUN cases passed without skips; physical devices and operator networks remain separate.
artifact: not_applicable
artifact_evidence: No distributable release is requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AUDIT-DIAGNOSTICS | DGN-1788583188016511 | 726 diagnostics tests; rebased combined native 1378 tests | passed |
| REQ-AUDIT-RELAY | DGN-1788583188681616 | SOCKS and Shadowsocks regressions; full-drop and paired-port regression | passed |
| REQ-AUDIT-DNS | DGN-1788583189338928 | DNS resolver loopback and signature regressions; combined native log | passed |
| REQ-AUDIT-ANDROID | DGN-1788583189913804 | Pinned core/app JVM regressions passed in CI run 33950376859 | passed |
| REQ-AUDIT-EVIDENCE | DGN-1788583190737094 | Audit report and successful exact-SHA CI run 33950376859 | passed |
| REQ-AUDIT-CAPTURE | DGN-1788584134462157 | Pinned PCAP/proxy JVM suites and full static analysis passed on the exact application SHA | passed |
| REQ-AUDIT-ECH | DGN-1788583189338928 | MASQUE and ECH 100 tests including missing/rejected callback | passed |
| REQ-AUDIT-IPC | DGN-1788586108973922 | Host 42 tests; Linux workspace CI passed resource-limit and truncated-prefix regressions | passed |
