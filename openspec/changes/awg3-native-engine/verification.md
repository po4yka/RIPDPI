---
task_id: TRN-1792949035079937
change: awg3-native-engine
commit_sha: ba228c17c
local: passed
local_evidence: Combined gate on fix/import-review: :app:testGithubFullDebugUnitTest, :app:lintGithubFullDebug, :core:service:lintDebug, :app:detekt, :app:ktlintCheck and the core data, runtime-state, engine-api, engine and service JVM suites plus their detekt/ktlint exited 0; cargo test, clippy -D warnings and fmt --check for ripdpi-warp-core exited 0; check_architecture_health --check and check_native_hotspot_budgets exited 0 with no new indicators.
remote_ci: required
remote_ci_evidence: Hosted gates on the pushed commit are required.
device: passed
device_evidence: Android arm64 emulator only (API 35 google_apis arm64-v8a image revision 9, pixel_6, emulator 37.2.12.0) against amneziawg-go v3.1.20260828 at ba228c17c (rebased on origin/main 9d6b867f7). Matrix 96/96 sessions (AmneziaVPN 5.x and S3=S4=0 control, 12 each per MTU 1280/1420 and idle/CPU load, 4 MiB TCP and 512 lockstep UDP datagrams per family, matching SHA-256, zero lost datagrams, zero Udp RcvbufErrors). Harness 5.x 8/8 with the wrong-key negative control failing as required. Before the rebase, 627f68bfb passed 160/160 and the harness control 8/8. No physical arm64 device was run.
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
| REQ-AWG3-FLOOR | TRN-1792949082866472 | Floor removed at 9a05a4d27 (WARP Amnezia at 67ddf0451) after the arm64 emulator matrix below; policy fixture records the emulator evidence; physical device not run | Passed on the emulator |

## Arm64 emulator evidence

API 35 `google_apis` arm64-v8a image revision 9, `pixel_6`, emulator 37.2.12.0, peer amneziawg-go v3.1.20260828 (`b5928efb6ca1`). One live peer serves four reconnect cycles. Each session moves 4 MiB TCP per address family, UDP pairs, DNS and a 512-datagram lockstep UDP series per family, all checked by SHA-256. Emulated mobile data was off, and runs started after the guest settled.

### Rebased on origin/main, ba228c17c

| Profile | MTU | Idle | 4 CPU hogs |
|---|---|---|---|
| AmneziaVPN 5.x (S1-S4 = 12, header protection, padding, trailers) | 1280 | 12/12 | 12/12 |
| AmneziaVPN 5.x | 1420 | 12/12 | 12/12 |
| S3=S4=0 control | 1280 | 12/12 | 12/12 |
| S3=S4=0 control | 1420 | 12/12 | 12/12 |

Zero lost UDP datagrams and zero Udp `RcvbufErrors` across the 96 sessions. `run-android-awg-interop.py --profile amneziavpn-5x` on committed ba228c17c: 8/8 cycles, nonce `awg5x-rebased-20261026c`; the wrong header protection key never reached readiness and the peer completed 0/4 exchanges.

### Before the rebase, 627f68bfb

160/160 sessions (20 per cell in the same layout). The harness ran 8b4a0d460 plus a candidate patch (SHA-256 `03ec9685b304…`) that was committed unchanged: 5.x 8/8 with the negative control failing, control 8/8 (nonces `awg5x-floor-lift-20261026b`, `awgctl-floor-lift-20261026b`). The rebase onto origin/main rewrote those commits as d7f35ac1b..5fc098fbb; the originals stay on the local branch `backup/awg-s34-floor-pre-rebase`.

### Before the fixes

Before the tunnel socket buffer fix (d7f35ac1b), the reconnect loop stalled a 4 MiB IPv4 download at MTU 1280 (1 of 12 peer runs) after the guest counted 1376 Udp `RcvbufErrors`. Before the harness changes (5fc098fbb), the S3=S4=0 control lost UDP echoes too: 4 of 13 sessions in one run (ENETUNREACH during default-network switches) and 1 of 297 fresh-peer sessions under CPU load. TRN-1792981644014401 records the root cause.
