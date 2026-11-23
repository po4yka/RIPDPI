---
task_id: DGN-1794734107413188
change: repair-telegram-diagnostic-evidence
commit_sha: de07fffc30b62ce9514de07a207e7983bbd1173e
local: passed
local_evidence: The earlier implementation passed 54 focused Rust tests, 1723 core diagnostics JVM tests, 13 app JVM tests, and offline source gates. The catalog follow-up passed 19 targeted JVM tests and checkDiagnosticsCatalog. The approved profile fixture is byte-identical to the generated asset.
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: A standalone ARM64 test binary ran the RIPDPI Telegram probes on a USB-connected Pixel 8 Pro with Android 17. All 10 bundled TCP endpoints responded on both direct and private-LAN SOCKS5 routes. WSS opened in 497 ms direct and 530 ms through SOCKS5. The proxy recorded all 11 expected CONNECT destinations. The same binary passed 34 native regressions, including cancellation and deadline handling. This device evidence covers the native probes.
artifact: not_applicable
artifact_evidence: No release artifact is required.
deployment: not_applicable
deployment_evidence: No deployment is required.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DIA08-HTTP | DGN-1794734190181416 | Rust HTTP/TLS fixture tests and the diagnostics JVM suite | Passed |
| REQ-DIA08-PATH | DGN-1794734191075772 | The generated profiles use explicit production IPv4 TCP endpoints on ports 443 and 80. On a physical Pixel, the native probe measured all 10 TCP endpoints and a successful WSS opening on each route. | Passed |
| REQ-DIA08-ABORT | DGN-1794734191951531 | Rust transfer abort and monitor checkpoint tests passed. | Passed |
| REQ-DIA08-REPORT | DGN-1794734192977477 | Rust aggregate and observation tests and JVM report, privacy, and presentation tests passed. | Passed |
