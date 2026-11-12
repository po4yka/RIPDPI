---
task_id: TRN-1794494451740063
change: tun06-transport-repairs
commit_sha: null
local: required
local_evidence: All eight repairs passed owning regression tests and independent review; combined source gates are pending.
remote_ci: not_applicable
remote_ci_evidence: This repair requires local gates before the authorized main push; no hosted run is claimed.
device: not_applicable
device_evidence: No device or external server acceptance is authorized; local contract tests cover this repair.
artifact: not_applicable
artifact_evidence: No APK or deployed Worker artifact is delivered by this source repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TRN-1794494451740063-F1 | TRN-1794494656917882 | Real workerd binary roundtrip red 1003 before repair; 6/6 current runtime tests and Node 5/5; independent review passed | passed |
| REQ-TRN-1794494451740063-F2 | TRN-1794494657639003 | OpenSSL-generated DC collision vectors; runtime ws_tunnel suite passed; independent review passed | passed |
| REQ-TRN-1794494451740063-F3 | TRN-1794494658746565 | Real workerd canonical production/test allowlist red before repair; 6/6 current runtime tests; independent review passed | passed |
| REQ-TRN-1794494451740063-F4 | TRN-1794494659472704 | Preserved partial seed regression red before repair; runtime ws_tunnel 20/20; independent review and file LoC gate passed | passed |
| REQ-TRN-1794494451740063-F5 | TRN-1794494660270476 | Real SOCKS5 DNS/TLS stop red before repair; four-crate suite 711 passed with 14 existing ignored; final prepared-stop hunk 22 affected tests passed; independent review passed | passed |
| REQ-TRN-1794494451740063-F6 | TRN-1794494660986335 | Four cold-read/cancellation regressions red before repair; runtime-state 418/418, ktlint and detekt passed; independent review passed | passed |
| REQ-TRN-1794494451740063-F7 | TRN-1794494661730257 | Independent OpenSSL vector red before repair; ws-tunnel 131 unit, 10 handshake-contract, 1 logging passed; independent review passed | passed |
| REQ-TRN-1794494451740063-F8 | TRN-1794495319927290 | Real workerd empty Close red with InvalidAccessError 1005 before repair; bidirectional empty/normal Close passed in 6/6 runtime tests; independent review passed | passed |
