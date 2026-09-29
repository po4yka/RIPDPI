---
task_id: RST-1790672261516782
change: lua-payload-function-compatibility
commit_sha: 2c1697db3bc95ea0df3fe4ded925c64db8aac1b7
local: required
local_evidence: docs/contributor/zapret2-lua-compatibility-verification.md
remote_ci: required
remote_ci_evidence: https://github.com/po4yka/RIPDPI/actions/runs/36555670276; Android API 27-37 and core gates passed, debug native size gate failed on the old baseline; approved measured baseline refresh awaits the next CI run.
device: required
device_evidence: Native NFQUEUE and packet checks plus eight actual APK JNI cases passed on API 37 ARM64. Actual C socket transfer failed closed under AOSP SELinux; app-domain root and successful VpnService.protect require a compatible root target.
artifact: required
artifact_evidence: Four-ABI nfqws2 outputs and actual producers passed. GithubFullDebug APK assembly, signature, exact Lua/license/source inventory, native ELF checks, and JNI execution passed. Broad assembleDebug requires the absent user Simple relay bundle.
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
| REQ-LUA-COMPAT-005 | RST-1790673469309998 | Native lifecycle, firewall timeout/collision, and Kotlin ownership passed; AOSP socket transfer failed closed, compatible app-root target required | partial |
| REQ-LUA-COMPAT-006 | RST-1790673468420507 | Four-ABI binaries and actual GithubFullDebug APK source/notice/ELF/signature checks passed | passed |
