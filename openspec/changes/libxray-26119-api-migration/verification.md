---
task_id: OUT-1794578142905704
change: libxray-26119-api-migration
commit_sha: null
local: passed
local_evidence: "Real linked GithubFullDebug build, 9876 Android unit tests, staticAnalysis, final app Full/Simple lint and ktlint, native protection tests, STUN regressions, artifact policy, and final ci-gates passed. Independent API/JNI/unsafe/change reviews reported no remaining findings."
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: "XrayRuntimeInstrumentedTest: two passed, zero failures/errors/skips on API 37 arm64 emulator, PAGE_SIZE=4096. Final log: /tmp/ripdpi-xray-device-fixed-final.log; both methods verified once with forbid-skips. Owned temporary AVD removed."
artifact: passed
artifact_evidence: "Local four-ABI producer and strict verifier passed; AAR SHA-256 3583c158f2a3e1263dca43d85d837df47e6910bd7c3758a39d980ef271f95a07. Receipt: native/xray/artifacts/libxray-artifact.json."
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-XRAY-API | OUT-1794578349001175 | Real AAR Java/JNI signatures and consumer rules inspected; linked Kotlin tests and two real Android runtime tests passed | passed |
| REQ-XRAY-PROTECT | OUT-1794578349001175 | `scripts/native/build-libxray.sh`: final-source native tests reject failed protection callbacks | passed |
| REQ-XRAY-OWNERSHIP | OUT-1794578349001175 | Producer host tests cover partial start, sticky close failure, and cleanup ownership | passed |
| REQ-XRAY-UDP | OUT-1794578350704862 | Producer host tests cover private RUF1 and ordinary SOCKS real sockets, including explicit IPv6 source and Darwin unbound-source port ownership | passed |
| REQ-XRAY-PROVENANCE | OUT-1794578350704862 | Full producer verified source/module/patch digests, generated Java API, four ABI ELFs, embedded Go 1.27.4, and 16 KiB LOAD alignment | passed |
| REQ-XRAY-PLATFORM | OUT-1794578350704862 | STUN suite and scoped IPv6/custom-network regressions passed; exact Android ioctl implementation passed as UID 65534 in isolated Linux; four real Android ABI links passed | passed |
| REQ-XRAY-VERSION-POLICY | OUT-1794578350704862 | Catalog validator tests cover 34 version vectors; service route-builder tests pass with actual module pin; unknown and pre-fix pseudo ancestors remain rejected | passed |

The final producer run passed 63 top-level tests across five real Go packages.
The host harness selects the production Android controller and process finder
in isolated source copies. It uses no replacement socket or protection stubs.
Android generated bindings and device execution are separate checks.

`scripts/native/build-libxray.sh` produced the real four-ABI AAR with linker
checks enabled. The ignored artifact and its digest-bound receipt are local
build outputs. No AAR binary is committed. The Linux ioctl test is not an
Android device scoped-STUN runtime test; Xray uses STUN codecs over its own
PacketConn and does not activate this default STUN dialer.

The Android runtime check first exposed the upstream Freedom finalrule that
blocks private targets from VLESS ingress. The test now permits only TCP to its
owned loopback echo port and blocks every other target. Production defaults,
identity checks, protection callbacks, timeouts, restart checks, and port-release
assertions are unchanged. The final two-test run passed after this specific fix;
the earlier failures and diagnostic logs remain separate local evidence.

This device result covers emulator loopback. It does not prove runtime behavior
on a 16 KiB image, a physical device, an external network, a minified release,
or the scoped-STUN dialer on Android. ELF 16 KiB alignment was checked separately.
