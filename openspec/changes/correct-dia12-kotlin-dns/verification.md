---
task_id: DGN-1794740137965522
change: correct-dia12-kotlin-dns
commit_sha: 744759bb4f16a781348e17c11329df2815223592
local: passed
local_evidence: "Offline Gradle JVM selection passed 206 tests (17 diagnostics-data, 156 diagnostics, 33 app), with AndroidTest Kotlin compilation and app and service lint. Locked native ECH suite passed seven tests. CI source gates and locked Cargo metadata passed. Diagnostics-data Detekt passed. Diagnostics and app Detekt reported only 32 findings reproduced on the unchanged upstream base."
remote_ci: required
remote_ci_evidence: Integration CI is pending on main.
device: required
device_evidence: API 27–29 fallback and real network route acceptance are pending. Host fixtures tested the protocol and state handling.
artifact: not_applicable
artifact_evidence: No release artifact is owned by this repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this repair.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DIA12-F1 | DGN-1794740261455701 | HTTPS resolver and ECH readiness tests verified distinct lookup outcomes and retained results after a target failure. App JVM tests exercised the presentation. | Passed on host |
| REQ-DIA12-F2 | DGN-1794740262133177 | Locked native ECH tests verified authenticated retry on an alternative loopback port, original inner SNI and rejection of a local alternative destination. Kotlin tests exercised binding selection. | Passed on host |
| REQ-DIA12-F3 | DGN-1794740262760036 | DnsHttpResponseTest verified accepted media, decoded size boundaries, chunked or gzip bodies and invalid media. | Passed on host |
| REQ-DIA12-F4 | DGN-1794740263945494 | DnsResponseSemanticsTest rejected malformed question, address, CNAME and SOA evidence. | Passed on host |
| REQ-DIA12-F5 | DGN-1794740264694894 | DnsWireEdnsProbeTest observed an OPT request and returned EDE. App JVM tests distinguish empty and unobserved EDE. | Passed on host |
| REQ-DIA12-F6 | DGN-1794740265270445 | DnsSemanticProbesTest and EchCancellationTest verified cancellation of active DNS I/O, including a loopback read ended before the response delay. | Passed on host |
| REQ-DIA12-F7 | DGN-1794740265789370 | DNS integrity, reachability and app JVM tests verified partial agreement, matching errors, resolver source and a shared address without redirect evidence. | Passed on host |
| REQ-DIA12-F8 | DGN-1794740266492751 | The wire parser and IPv6 DirectDns loopback verified mapped AAAA with its IPv6 family. App packet-smoke tests exercised the caller. | Passed on host |
| REQ-DIA12-F9 | DGN-1794740267020326 | DohQueryIdentityTest verified ID zero on DoH POST, GET fallback and HTTPS RR. DnsMessagesTest verified private-option sizing. | Passed on host |
| REQ-DIA12-F10 | DGN-1794740267549719 | DnsMessagesTest and DnsResponseSemanticsTest verified local strict parsing, effective negative TTL and the full OPT control word. | Passed on host |
