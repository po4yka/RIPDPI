---
task_id: AND-1795444347241489
change: exclude-play-privileged-features
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: Release publication is a separate operation.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DISTRIBUTION-ARTIFACT | AND-1795444402230111 | All three arm64 debug APKs built and checked in one checkout. | Local pass |
| REQ-DISTRIBUTION-RUNTIME | AND-1795444402897115 | Standard and extended Android tests and native build and migration checks. | Local pass |
| REQ-DISTRIBUTION-RELEASE | AND-1795444403544089 | Signed release artifact checks and device acceptance. | Pending |

## Local results

- Merged Play, F-Droid, and GitHub debug assets for arm64-v8a in one Gradle invocation. The helper executable, nfqws2, and the generated zapret2 asset tree were present in F-Droid and GitHub only.
- The Play-only asset merge task graph excluded the extended asset stage and both executable producer tasks.
- The Play arm64 debug APK and its universal output passed the package scanner. The build used Play JNI and the debug configuration without libXray.
- One Gradle invocation assembled the Play, GitHub, and F-Droid debug APKs. The artifact scanner passed all three arm64 APKs and found the two required executables in each extended edition.
- The full Play and GitHub app JVM test suites passed. F-Droid Kotlin compiled. Both diagnostics variants passed their full JVM test suites.
- The source gates passed in a clean checkout. The canonical Linux runtime-platform API snapshot requires CI.
- The Play APK recorded eight packets after capture consent and VPN connection on a non-rooted Android 35 emulator. The viewer opened the file after VPN disconnection and app restart.
- The capture action created no file with an active VPN and capture consent off. It also created no file with capture consent on and VPN disconnected.
- The Play APK started the local proxy on the same emulator. Its SOCKS5 listener answered the client greeting on the configured loopback port.
