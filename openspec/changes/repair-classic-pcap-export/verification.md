---
task_id: DGN-1794755342253604
change: repair-classic-pcap-export
commit_sha: bc60e47377d5b45bc7c4dfd926bed3f211614087
local: passed
local_evidence: 66 native tests; 67 JVM tests (34 PCAP module, 10 diagnostics, 23 app); app and service lint; debug and test APK assembly; source and task gates; TShark and tcpdump agreed on 40/55 after redaction.
remote_ci: required
remote_ci_evidence: Hosted validation is pending.
device: required
device_evidence: Two fresh-process JNI tests passed on a leased Android 15 ARM64 emulator. Live VPN capture and SAF provider acceptance remain pending.
artifact: not_applicable
artifact_evidence: No release artifact is requested.
deployment: not_applicable
deployment_evidence: No deployment is requested.
---

# Verification

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AUX01-F1 | DGN-1794755433954920 | JVM PCAP viewer route test and debug APK assembly. | Pass locally |
| REQ-AUX01-F2 | DGN-1794755447878075 | JUnit permission rejection, unavailable permission, and withdrawal tests. | Pass locally |
| REQ-AUX01-F3 | DGN-1794755448757845 | Native writer and JVM lease tests for capacity, timeout, drops, and receipt ownership. | Pass locally |
| REQ-AUX01-F4 | DGN-1794755449704467 | Rust redaction tests and independent TShark/tcpdump decode of 40/55 output. | Pass locally |
| REQ-AUX01-F5 | DGN-1794755450473667 | Rust and JVM header and length tests plus the viewer memory bound test. | Pass locally |
| REQ-AUX01-F6 | DGN-1794755451317957 | Native storage retention tests and diagnostics worker test. | Pass locally |
| REQ-AUX01-F7 | DGN-1794755452164214 | Rust truncated record tests and JVM staging, cancellation, and document rollback tests. | Pass locally |
| REQ-AUX01-F8 | DGN-1794755453075398 | Rust writer bounds/rotation tests and TUN loopback test. | Pass locally |
| REQ-AUX01-F9 | DGN-1794755453941039 | Native inaccessible-store tests and Android capture listing boundary. | Pass locally |

## Gates

- `just ci-gates` and `./taskctl validate` passed.
- `cargo test --locked --jobs 4 -p ripdpi-pcap` passed 30 tests. The `ripdpi-tunnel-android --lib pcap` filter passed 35 tests. The `ripdpi-tunnel-core --test tun_e2e` observer case passed. The native tests ran again after the DPI-38 integration.
- `./gradlew :core:pcap-export:testDebugUnitTest`, the filtered diagnostics/app tests, `:app:lintGithubFullDebug`, and `:core:service:lintDebug` passed. Both debug APK tasks completed.
- On an Android 15 ARM64 emulator, both cases in `PcapBridgeFreshProcessInstrumentedTest` passed. The APKs came from `42db6b8d3`, which has the same PCAP production code.
- Independent general and JNI review verified the final capture, parser, and export paths.
