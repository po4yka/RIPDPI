---
task_id: DGN-1790329640901759
change: show-real-local-packet-captures
commit_sha: null
local: passed
local_evidence: "PCAP app and controller unit tests passed; staticAnalysis and Android lint passed with ripdpi.skipNativeBuild=true."
remote_ci: required
remote_ci_evidence: null
device: blocked
device_evidence: "No connected Android device was available for runtime inspection of a recorded capture."
artifact: not_applicable
artifact_evidence: No published artifact is owned by this UI fix.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DGN-1790329640901759-001 | DGN-1790329739827433 | `PcapControllerMetadataTest`, `PcapCaptureContentTest`, and `:app:lintGithubFullDebug` passed locally | Local passed; device pending |
| REQ-DGN-1790329640901759-002 | DGN-1790329739827433 | `PcapCaptureContentTest` and `RipDpiNavHostLogicTest` passed locally | Local passed; device pending |
| REQ-DGN-1790329640901759-003 | DGN-1790329739827433 | `PcapCaptureContentTest` covers traversal, symlink, invalid and missing capture files | Local passed; device pending |
