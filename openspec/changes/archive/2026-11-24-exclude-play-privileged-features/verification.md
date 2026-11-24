---
task_id: AND-1795444347241489
change: exclude-play-privileged-features
commit_sha: d1b75749a2ef4b01e01b3b6648dfdd475f35487e
local: passed
local_evidence: Implementation validation covered debug packages and both diagnostic test suites. On d0ee0f52e5, the Play PCAP test, 22 release scanner and promotion contract tests, and just ci-gates passed.
remote_ci: passed
remote_ci_evidence: Full CI run 45441455734 passed on attempt 2 on the recorded source, including all three debug and release distribution jobs.
device: passed
device_evidence: The non-root API 35 emulator passed the VPN, proxy greeting, and PCAP consent, restart, and viewer checks documented below. A physical root device is outside the Android app acceptance scope.
artifact: passed
artifact_evidence: Play, GitHub, and F-Droid arm64 debug APKs passed the package checks. The release workflow checks the signed AAB and its APK splits before promotion.
deployment: not_applicable
deployment_evidence: Release publication is a separate operation.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DISTRIBUTION-ARTIFACT | AND-1795444402230111 | All three arm64 debug APKs passed the local package scanner. Hosted debug builds and packaged ELF and size checks passed. | passed |
| REQ-DISTRIBUTION-RUNTIME | AND-1795444402897115 | Standard and extended Android tests and the non-rooted API 35 VPN, proxy and PCAP checks passed. | passed |
| REQ-DISTRIBUTION-RELEASE | AND-1795444403544089 | The release scanner and promotion contract tests and all three hosted release builds passed. Signed AAB and split APK verification remains a promotion gate. | passed |

## Recorded implementation and current local results

- The implementation evidence committed in `ff7df36e07` includes the APK scans and API 35 emulator tests below. It predates the current source checks.
- Merged Play, F-Droid, and GitHub debug assets for arm64-v8a in one Gradle invocation. The helper executable, nfqws2, and the generated zapret2 asset tree were present in F-Droid and GitHub only.
- The Play-only asset merge task graph excluded the extended asset stage and both executable producer tasks.
- The Play arm64 debug APK and its universal output passed the package scanner. The build used Play JNI and the debug configuration without libXray.
- One Gradle invocation assembled the Play, GitHub, and F-Droid debug APKs. The artifact scanner passed all three arm64 APKs and found the two required executables in each extended edition.
- The full Play and GitHub app JVM test suites passed. F-Droid Kotlin compiled. Both diagnostics variants passed their full JVM test suites.
- The source gates passed in a clean checkout. The canonical Linux runtime-platform API snapshot requires CI.
- The Play APK recorded eight packets after capture consent and VPN connection on a non-rooted Android 35 emulator. The viewer opened the file after VPN disconnection and app restart.
- The capture action created no file with an active VPN and capture consent off. It also created no file with capture consent on and VPN disconnected.
- The Play APK started the local proxy on the same emulator. Its SOCKS5 listener answered the client greeting on the configured loopback port.
- The release scanner and release promotion contract passed 22 Python tests. The signed AAB and split APK checks run in the release workflow before promotion.

## Hosted CI

[Full CI run 45441455734](https://github.com/po4yka/RIPDPI/actions/runs/45441455734/attempts/2)
passed at `d1b75749a2`. Play, GitHub, and F-Droid passed debug APK assembly
and packaged ELF and size checks. All three release shards passed assembly
and packaged ELF and JNI mapping checks. The Play debug shard passed after
Maven Central returned HTTP 429 on the first attempt.
