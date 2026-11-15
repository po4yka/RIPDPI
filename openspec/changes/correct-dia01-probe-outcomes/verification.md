---
task_id: DGN-1794730862541358
change: correct-dia01-probe-outcomes
commit_sha: 03376f3d34fea2265a8d8e306d972c0884cef605
local: passed
local_evidence: Nine Rust packages passed 867 tests with five existing ignores. The diagnostics JVM module passed 1717 tests. The local CI source gates and locked Cargo metadata checks passed.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: Physical Android path and protection acceptance needs a controlled LAN fixture.
artifact: not_applicable
artifact_evidence: No release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DIA01-CONTROL | DGN-1794730953929803 | SOCKS5 and WSS stop a pending open, and Web and Telegram retain the correct administrative outcome | Passed |
| REQ-DIA01-WS | DGN-1794730965297275 | Loopback TLS and HTTP/1.1 fixtures check fallback, authority, port, budget, and negotiated subprotocol | Passed |
| REQ-DIA01-TLS | DGN-1794730966185181 | TLS fixtures check alerts, certificate failures, and ECH retry evidence. Monitor and Kotlin tests check reports and policy | Passed |
| REQ-DIA01-PATH | DGN-1794730967094598 | SOCKS5 and Web fixtures check origin address provenance and opening time. Report tests check selected TTL evidence and socket leg | Passed |

The host JVM suite ran without the Android native build. The Android-only
socket option and VPN protection path need device evidence. The WSS fixtures
use local endpoints. Compatibility with the public Telegram endpoint is unverified.
