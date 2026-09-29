---
task_id: RST-1790672261516782
change: lua-payload-function-compatibility
commit_sha: null
local: required
local_evidence: docs/contributor/zapret2-lua-compatibility-verification.md
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: Native NFQUEUE and packet checks passed on API 37 ARM64; APK app-domain root and VpnService.protect require an app-granting root target.
artifact: required
artifact_evidence: Four-ABI nfqws2 outputs and actual native producers passed; APK assembly is pending.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LUA-COMPAT-001 | RST-1790672361795275 | Combined Rust and Kotlin regression gates in the verification note | passed |
| REQ-LUA-COMPAT-002 | RST-1790672362708202 | Combined Rust and Kotlin regression gates in the verification note | passed |
| REQ-LUA-COMPAT-003 | RST-1790672362708202 | Combined Rust and Kotlin regression gates in the verification note | passed |
| REQ-LUA-COMPAT-004 | RST-1790673470200636 | Full ABI inventory and six libraries passed on Linux and API 37; native packet capture passed | passed |
| REQ-LUA-COMPAT-005 | RST-1790673469309998 | Native lifecycle, firewall timeout/collision, and Kotlin ownership gates passed; APK root-domain check remains unavailable | partial |
| REQ-LUA-COMPAT-006 | RST-1790673468420507 | Four-ABI reproducible binaries, source/notice inventory, and ELF checks passed; APK assembly pending | partial |
