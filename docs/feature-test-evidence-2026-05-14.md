# Feature Test Evidence - 2026-05-14

This is the current evidence ledger for `docs/feature-test-checklist.md` at
commit `ce7e4cc6`. It records what was verified in the local application test
pass and what still requires a dedicated device, network, relay, or remote CI
environment.

This file is not a release sign-off. Rows marked `Gap` or `Partial` are still
open checklist work.

## Tested Build and Devices

| Item | Evidence |
| --- | --- |
| Git head | `ce7e4cc6 fix(build): exclude coroutine debug probe asset` |
| Physical device | Pixel 8 Pro, Android 16 / API 36, non-rooted |
| Emulator smoke | API 34 AVD fresh-start UI smoke |
| Release APK inspected | `app/build/outputs/apk/github/release/app-github-arm64-v8a-release.apk` |
| Lab artifacts | `test-lab/artifacts/vpn-e2e-20260514-013348/probe-device-vpn.json`, `test-lab/artifacts/probe-device-diagnostics.json`, `test-lab/artifacts/test-lab-artifacts-20260513T221424Z.tar.gz` |

## Command Evidence

| Checklist area | Command or artifact | Result |
| --- | --- | --- |
| Static analysis | `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --no-daemon` | Pass, 518 tasks, no failures |
| Release verification build | `./gradlew :app:assembleGithubRelease -Pripdpi.nativeAbisOverride=arm64-v8a -Pripdpi.nativeAbiParallelism=1 --no-daemon` | Pass |
| Build logic checks | `./gradlew :build-logic:convention:check --no-daemon` | Pass |
| Physical connected tests | `app/build/outputs/androidTest-results/connected/debug/TEST-Pixel 8 Pro - 16-_app-.xml` | 86 tests, 0 failures, 0 errors, 21 environment skips |
| Root detector tests | `core/service/build/test-results/testDebugUnitTest/TEST-com.poyka.ripdpi.services.RootDetectorTest.xml` | 4 tests, 0 failures |
| Root helper manager tests | `core/service/build/test-results/testDebugUnitTest/TEST-com.poyka.ripdpi.services.RootHelperManagerTest.xml` | 4 tests, 0 failures |
| Test-lab doctor | `test-lab/artifacts/doctor.json` | 11 pass, 0 fail, 0 skip |
| VPN E2E physical probe | `test-lab/artifacts/vpn-e2e-20260514-013348/probe-device-vpn.json` | DNS, HTTP, HTTPS, TCP, UDP pass; QUIC marked unsupported by debug probe |
| Diagnostics-only probe | `test-lab/artifacts/probe-device-diagnostics.json` | DNS, HTTP, HTTPS, TCP, UDP pass; QUIC marked unsupported by debug probe |
| Test-lab archive redaction | `test-lab/artifacts/test-lab-artifacts-20260513T221424Z.tar.gz` | Archive contains redacted logcat, dumpsys, lab env, probe artifact |
| Release APK leak check | `zipinfo`, `apkanalyzer`, `dexdump` string scans | No test-lab, generated lab data, debug probe asset, instrumentation, or packet-smoke strings found |

## Checklist Section Coverage

| Checklist section | Status | Evidence | Remaining work |
| --- | --- | --- | --- |
| How to use | Partial | Changed areas identified as test-lab, root detection, diagnostics archive redaction, Android build packaging, native test stability | Full release-candidate matrix is not complete |
| Test dimensions | Partial | Debug, release verification build, physical arm64, API 36, API 34 emulator, VPN, diagnostics-only, local dual-stack lab service set | API 27, API 31, API 35 emulator/device runs; rooted physical device; cellular, handover, private DNS, IPv4-only, IPv6-only, captive or limited network |
| Core smoke matrix | Partial | Physical VPN E2E, diagnostics probe, connected tests, release APK inspection, static analysis, Roborazzi and unit gates from this pass | Proxy curl smoke outside instrumentation, full relay matrix, GitHub Actions confirmation after push |
| App shell, navigation, and settings | Partial | API 34 fresh-start UI smoke reached onboarding, Home, and Settings; connected UI tests passed with no failures | Manual TalkBack, RTL, large font, dynamic-color contrast, process-kill and migrated-install checks |
| Proxy service | Partial | Service lifecycle connected tests passed; lab services and probes cover local TCP/UDP endpoints | Direct SOCKS5 curl, port conflict, command-line strategy mode, relay-on proxy combinations |
| VPN service | Partial | Physical VPN probe established TUN, proxy readiness, protected egress, IPv4 route, DNS/HTTP/HTTPS/TCP/UDP success; notification and permission flow instrumentation passed | Cellular handover, private DNS, IPv4-only, IPv6-only, always-on/lockdown, relay-on VPN, Unix socket protection fallback runtime path |
| DNS and resolver resilience | Partial | Diagnostics and VPN probes resolved local DNS; DNS anomaly and failover behavior covered by existing unit and native tests in this pass scope | DoH, DoT, DNSCrypt, DoQ provider success on device; handover behavior; OS private DNS runtime validation |
| Packet strategy features | Partial | Packet-smoke instrumentation report present with 0 failures; native packet and root-helper crates passed targeted tests; release APK inspected for debug-only test strings | Full per-family runtime matrix across IPv4, IPv6, TCP, UDP, QUIC, DTLS, Lua rawsend, and rooted privileged strategies |
| Relay and tunneling paths | Partial | Mock relay readiness verified by test-lab doctor; archive includes deterministic lab state | Production relay paths still need provider-specific validation: VLESS Reality, VLESS xHTTP, WARP, Cloudflare Tunnel, MASQUE, Hysteria2, TUIC v5, ShadowTLS v3, NaiveProxy, WebTunnel, obfs4, Snowflake, Google Apps Script path |
| Diagnostics workflows | Partial | Physical diagnostics-only probe passed DNS/HTTP/HTTPS/TCP/UDP; connected diagnostics tests passed; archive redaction covered | Full matrix audit, home composite stage ordering, cancellation UI busy-state, command-line settings gating, RAW_PATH with VPN stop behavior |
| Autolearn and remembered networks | Gap | No dedicated runtime evidence in this ledger | Validate recommendation persistence, low-confidence rejection, network identity matching, reset, capacity limit, schema migration, export/import behavior |
| Browser and HTTP stack | Partial | Connected tests and parser tests present in the physical Android test report; secure HTTPS probe succeeded | Browser route handoff after service restart, HTTP/2 retry eligibility, ECH platform gating, parser fault isolation matrix |
| Root helper and privileged operations | Partial | Non-rooted physical degradation confirmed; API 34 rooted emulator shell showed `su 0 id` works; root detector and helper-manager unit tests passed; native IPC and privileged-operation tests passed | Rooted physical device app lifecycle, helper APK extraction through app startup, helper readiness, actual privileged send operations, helper stop cleanup |
| Logging, history, export, and privacy | Partial | Diagnostics archive redaction was fixed and verified; release APK omits lab/test artifacts; archive contains redacted logcat | Diagnostics history clear/retention, export failure path, release log verbosity on installed release build |
| UI, Compose, localization, and accessibility | Partial | Roborazzi gates passed earlier in this pass; locale key diff and README selector checks passed; API 34 UI smoke had no fatal logcat markers | TalkBack, RTL Persian runtime, large font runtime, Chinese/German/Spanish compact text fit, all state permutations on Home/Diagnostics/History |
| Test-lab and automation tooling | Partial | `doctor.sh`, `start.sh`, `stop.sh`, UDP echo, mock relay, toxiproxy delay/reset, archive redaction, shell/YAML/Compose validation all passed | Linux netem routed VM packet-loss scenario and GitHub Actions run remain unverified locally |
| CI, release, and supply chain | Partial | Local static analysis, release assemble, build logic check, cargo-deny, packet smoke, coverage, Roborazzi, Rust workspace tests, and release APK inspection passed in this pass | Remote CodeQL, remote CI matrix, native bloat job, offline analytics workflow, mutation-testing scope confirmation |
| Runtime mode by DNS by relay | Partial | VPN with local DNS and relay-off path covered; diagnostics-only with local DNS covered; test-lab mock relay readiness covered | Proxy mode matrix, plain override, encrypted DNS, relay-on device runs |
| Runtime mode by packet strategy | Partial | Instrumented packet-smoke and native packet tests passed; QUIC unsupported is reported as degraded by Android debug probe | Runtime matrix across proxy, VPN, diagnostics, and rooted strategies |
| Relay by runtime mode | Partial | Mock relay readiness only | All production relay paths remain open |
| Network fault matrix | Partial | Toxiproxy delay and reset scenarios applied and cleared; probe reports degraded QUIC unsupported rather than stale success | UDP packet loss in routed VM, malformed HTTP, TLS alert, process kill, handover, DNS timeout/reset across proxy/VPN/diagnostics |
| Feature definition of done | Partial | Direct tests, runtime smoke, failure injection, redaction, docs, and CI gates covered for the local test-lab/root/build-packaging fixes | Manual and provider-backed release matrix remains open |

## Bugs Found and Fixed During This Pass

| Finding | Fix commit |
| --- | --- |
| Test-lab doctor could report success when services were dead because wrapper logic suppressed failures | `c1c4eed4 fix(test-lab): fail doctor on dead services` |
| Emulator root detection missed AOSP `su 0 id` behavior | `46c2b1fb fix(service): support emulator su detection` |
| Diagnostics support archive redaction did not cover endpoint-like logcat fields | `d220cc47 fix(diagnostics): redact logcat archive endpoints` |
| Release APK included `DebugProbesKt.bin` from coroutine debug probes | `ce7e4cc6 fix(build): exclude coroutine debug probe asset` |

## Current Open Gaps

These are not product pass/fail findings yet; they are missing environments or
unexecuted manual rows from `docs/feature-test-checklist.md`.

- Android API 27, API 31, and API 35 runtime coverage. Local SDK metadata did
  not expose usable API 31 or API 35 system images, and no API 27 image was
  installed.
- Rooted physical device coverage for helper extraction, helper startup,
  privileged packet operations, readiness timeout, and cleanup.
- Physical cellular, Wi-Fi-to-cellular, cellular-to-Wi-Fi, private DNS,
  IPv4-only, IPv6-only, captive, and limited-path network runs.
- Full production relay provider matrix across proxy, VPN, diagnostics,
  restart, invalid credential, reset, timeout, malformed response, DNS fallback,
  and network handover.
- Manual accessibility and layout coverage for TalkBack, large font, RTL
  Persian, compact Chinese text, and wider German/Spanish labels.
- Linux routed VM netem packet-loss scenario.
- Remote GitHub Actions, CodeQL, native bloat, offline analytics, and mutation
  workflow confirmation for the local commits ahead of `origin/main`.

## Next Concrete Runs

1. Install or repair emulator system images for API 27, API 31, and API 35,
   then repeat fresh-start UI, foreground-service, and VPN permission smoke.
2. Run a rooted physical-device pass for root-helper startup and privileged
   operations.
3. Run a manual network pass for cellular, handover, private DNS, IPv4-only, and
   IPv6-only.
4. Execute the provider-backed relay matrix, starting with mock relay parity in
   proxy and VPN mode, then one production relay at a time.
5. Push or dispatch the branch and verify remote CI, CodeQL, native bloat,
   offline analytics, and mutation workflows.
