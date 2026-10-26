---
task_id: TRN-1792949035079937
change: awg3-native-engine
commit_sha: 627f68bfb
local: passed
local_evidence: Combined gate on fix/import-review: :app:testGithubFullDebugUnitTest, :app:lintGithubFullDebug, :core:service:lintDebug, :app:detekt, :app:ktlintCheck and the core data, runtime-state, engine-api, engine and service JVM suites plus their detekt/ktlint exited 0; cargo test, clippy -D warnings and fmt --check for ripdpi-warp-core exited 0; check_architecture_health --check and check_native_hotspot_budgets exited 0 with no new indicators.
remote_ci: required
remote_ci_evidence: Hosted gates on the pushed commit are required.
device: passed
device_evidence: Android arm64 emulator only (API 35 google_apis arm64-v8a image revision 9, pixel_6, emulator 37.2.12.0) against amneziawg-go v3.1.20260828 at 627f68bfb. Matrix 160/160 sessions (AmneziaVPN 5.x and S3=S4=0 control, 20 each per MTU 1280/1420 and idle/CPU load, 4 MiB TCP and 512 lockstep UDP datagrams per family, matching SHA-256, zero lost datagrams, zero Udp RcvbufErrors). Harness 5.x 8/8 with the wrong-key negative control failing as required, control 8/8. No physical arm64 device was run.
artifact: not_applicable
artifact_evidence: No release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG3-WIRE | TRN-1792949081837648 | Live run against amneziawg-go v3.1.20260828 (MTU 1280 and 1420, two sessions each; wrong-key negative control failed every session) and Go-derived vectors in awg_v3_tests.rs | Passed locally |
| REQ-AWG3-HOSTILE | TRN-1792949082316022 | unauthenticated_transport_does_not_grow_the_udp_window_and_growth_is_capped; short and cookie cases in only_boringtun_authenticated_transport_may_teach_the_udp_window; live rerun 4/4 sessions against amneziawg-go v3.1 | Passed locally |
| REQ-AWG3-CONTRACT | TRN-1792949082316022 | core:data, engine-api, engine and service JVM suites; ignored-key tests | Passed locally |
| REQ-AWG3-FLOOR | TRN-1792949082866472 | Floor removed at a4d6b84e6 after the arm64 emulator matrix below; policy fixture records the emulator evidence; physical device not run | Passed on the emulator |

## Arm64 emulator evidence

API 35 `google_apis` arm64-v8a image revision 9, `pixel_6`, emulator 37.2.12.0, peer amneziawg-go v3.1.20260828 (`b5928efb6ca1`). RIPDPI 627f68bfb (the harness ran 8b4a0d460 plus a candidate patch with SHA-256 `03ec9685b304…`, committed unchanged as c70f50658..627f68bfb). One live peer serves four reconnect cycles. Each session moves 4 MiB TCP per address family, UDP pairs, DNS and a 512-datagram lockstep UDP series per family, all checked by SHA-256. Emulated mobile data was off, and runs started after the guest settled.

| Profile | MTU | Idle | 4 CPU hogs |
|---|---|---|---|
| AmneziaVPN 5.x (S1-S4 = 12, header protection, padding, trailers) | 1280 | 20/20 | 20/20 |
| AmneziaVPN 5.x | 1420 | 20/20 | 20/20 |
| S3=S4=0 control | 1280 | 20/20 | 20/20 |
| S3=S4=0 control | 1420 | 20/20 | 20/20 |

Zero lost UDP datagrams and zero Udp `RcvbufErrors` across the 160 sessions. `run-android-awg-interop.py --profile amneziavpn-5x`: 8/8 cycles, nonce `awg5x-floor-lift-20261026b`; the wrong header protection key never reached readiness and the peer completed 0/4 exchanges. `--profile awg-3.1-ranges-v1`: 8/8 cycles, nonce `awgctl-floor-lift-20261026b`.

Before the tunnel socket buffer fix (c70f50658), the same reconnect loop stalled a 4 MiB IPv4 download at MTU 1280 (1 of 12 peer runs) after the guest counted 1376 Udp `RcvbufErrors`. TRN-1792981644014401 records that root cause.
