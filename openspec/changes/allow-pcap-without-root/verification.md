---
task_id: DGN-1795412913098712
change: allow-pcap-without-root
commit_sha: null
local: passed
local_evidence: 171 app tests, 47 VPN runtime tests, 36 PCAP tests, both Android lint tasks, and just ci-gates on the staged source snapshot.
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: Android 15 arm64 Play Store emulator. The app requested separate capture consent, recorded one packet on the active VPN with root mode off, rejected a capture after disconnect, and displayed the saved packet in the viewer.
artifact: passed
artifact_evidence: The arm64 GitHub debug APK assembled from a source snapshot with the local Xray stub.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PCAP-ACCESS | DGN-1795412979499827 | PCAP controller and UI tests, and live VPN capture and offline viewing on Android 15. | Passed |
