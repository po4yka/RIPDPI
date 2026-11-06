---
task_id: DNS-1793945565828575
change: kotlin-dns-library-codec
commit_sha: null
local: required
local_evidence: All 7219 affected unit tests passed. Final diagnostics-data tests and Detekt passed after the resolver simplification. CI source gates and independent review passed. Full staticAnalysis will be rerun after rebase.
remote_ci: required
remote_ci_evidence: Pending integrated main push and hosted checks.
device: not_applicable
device_evidence: No Android routing API or permission change; real local UDP and TCP fixtures verify transport. External-network and device acceptance are not claimed.
artifact: not_applicable
artifact_evidence: No release artifact is requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-KDNS-WIRE | DNS-1793945680585019 | diagnostics-data 87 tests; diagnostics 1658 tests; independent review | Passed |
| REQ-KDNS-TCP | DNS-1793945681167350 | DirectDnsTransportTest: real loopback UDP/TCP exchange, large datagram and question rejection | Passed |
| REQ-KDNS-PROBES | DNS-1793945681717226 | Service 2061 tests; app 3413 tests; diagnostics HTTPS/DoQ regressions | Passed |

## Local observations

- The pre-change reproduction accepted a query packet as a response. DirectDnsTest failed before the replacement.
- Independent review found and then verified fixes for high-bit TTL/OPT loss in dnsjava and false DoQ success on truncated messages.
- The first complete service run had one timeout in the unchanged PluggableTransportSocksBridgeWireTest. A complete rerun with two Gradle workers passed all 2061 service tests, and the next combined run passed again. No assertion or timeout was changed.
- Native dependencies built after removing the ambient CARGO_BUILD_JOBS override. The missing pinned NaiveProxy artifact was fetched with the repository script and SHA-256 verification.
