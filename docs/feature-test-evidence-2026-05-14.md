# Feature Test Evidence - 2026-05-14

This is the current evidence ledger for `docs/feature-test-checklist.md`. It
records what was verified in the local application test pass and what still
requires a dedicated device, network, relay, or remote CI environment.

This file is not a release sign-off. Rows marked `Gap` or `Partial` are still
open checklist work.

## Tested Build and Devices

| Item | Evidence |
| --- | --- |
| Git history | Evidence commits are recorded on the current branch; use `git log --oneline` for exact hashes |
| Physical device | Pixel 8 Pro, Android 16 / API 36, non-rooted |
| Emulator smoke | API 34 AVD fresh-start UI smoke; API 35 Persian large-font forced-RTL UI smoke |
| Release APK inspected | `app/build/outputs/apk/github/release/app-github-arm64-v8a-release.apk` |
| Lab artifacts | `test-lab/artifacts/vpn-e2e-20260514-013348/probe-device-vpn.json`, `test-lab/artifacts/probe-device-diagnostics.json`, `test-lab/artifacts/proxy-smoke-20260514-030750/probe-device-proxy.json`, `test-lab/artifacts/api27-proxy-smoke-20260514-033259/probe-emulator-proxy.json`, `test-lab/artifacts/api31-proxy-smoke-20260514-033411/probe-emulator-proxy.json`, `test-lab/artifacts/api35-proxy-smoke-20260514-033526/probe-emulator-proxy.json`, `test-lab/artifacts/api35-fa-large-font-ui-20260514-034335/summary.txt`, `test-lab/artifacts/release-log-verbosity-20260514-040812/summary.txt`, `test-lab/artifacts/proxy-port-conflict-20260514-041518/summary.txt`, `test-lab/artifacts/proxy-process-kill-live-relaunch-fixed-20260514-042706/summary.txt`, `test-lab/artifacts/test-lab-artifacts-20260513T221424Z.tar.gz` |

## Command Evidence

| Checklist area | Command or artifact | Result |
| --- | --- | --- |
| Static analysis | `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --no-daemon` | Pass, 518 tasks, no failures |
| Release verification build | `./gradlew :app:assembleGithubRelease -Pripdpi.nativeAbisOverride=arm64-v8a -Pripdpi.nativeAbiParallelism=1 --no-daemon` | Pass |
| Build logic checks | `./gradlew :build-logic:convention:check --no-daemon` | Pass |
| Physical connected tests | `app/build/outputs/androidTest-results/connected/debug/TEST-Pixel 8 Pro - 16-_app-.xml` | 86 tests, 0 failures, 0 errors, 21 environment skips; includes `ServiceLifecycleIntegrationTest.proxyServiceUsesResolvedCommandLinePreferences` and related proxy/VPN lifecycle tests |
| Root detector tests | `core/service/build/test-results/testDebugUnitTest/TEST-com.poyka.ripdpi.services.RootDetectorTest.xml` | 4 tests, 0 failures |
| Root helper manager tests | `core/service/build/test-results/testDebugUnitTest/TEST-com.poyka.ripdpi.services.RootHelperManagerTest.xml` | 4 tests, 0 failures |
| Test-lab doctor | `test-lab/artifacts/doctor.json` | 11 pass, 0 fail, 0 skip |
| VPN E2E physical probe | `test-lab/artifacts/vpn-e2e-20260514-013348/probe-device-vpn.json` | DNS, HTTP, HTTPS, TCP, UDP pass; QUIC marked unsupported by debug probe |
| Diagnostics-only probe | `test-lab/artifacts/probe-device-diagnostics.json` | DNS, HTTP, HTTPS, TCP, UDP pass; QUIC marked unsupported by debug probe |
| Test-lab archive redaction | `test-lab/artifacts/test-lab-artifacts-20260513T221424Z.tar.gz` | Archive contains redacted logcat, dumpsys, lab env, probe artifact |
| Release APK leak check | `zipinfo`, `apkanalyzer`, `dexdump` string scans | No test-lab, generated lab data, debug probe asset, instrumentation, or packet-smoke strings found |
| Offline analytics unit tests | `python3 -m unittest scripts.tests.test_offline_analytics_pipeline` | 7 tests, OK |
| Offline analytics sample corpus | `python3 -m scripts.analytics.pipeline run-all --manifest scripts/analytics/sample-corpus.json --output-dir /tmp/ripdpi-offline-analytics-20260514` | Pass |
| Native bloat check | `python3 scripts/ci/verify_native_bloat.py --report-json /tmp/ripdpi-native-bloat-20260514.json --report-md /tmp/ripdpi-native-bloat-20260514.md` | Pass against checked-in baseline |
| Autolearn and remembered-network unit tests | `./gradlew :core:data:testDebugUnitTest :core:diagnostics-data:testDebugUnitTest :core:diagnostics:testDebugUnitTest --tests ... -Pripdpi.skipNativeBuild=true --no-daemon` | `HostAutolearnSettingsTest` 4 tests, `RememberedNetworkPolicyStoreTest` 7 tests, `DiagnosticsStrategyProbeRecommendationPersistenceTest` 3 tests; all pass |
| Proxy and relay unit tests | `./gradlew :core:service:testDebugUnitTest :core:engine:testDebugUnitTest :app:testGithubDebugUnitTest --tests ... -Pripdpi.skipNativeBuild=true --no-daemon` | 127 focused tests, 0 failures across proxy supervisors, relay runtime config, relay UI validation, MASQUE credentials, Cloudflare publish/geohash, NaiveProxy policy, and proxy JSON/preference mapping |
| Diagnostics workflow, history, and export unit tests | `./gradlew :core:diagnostics:testDebugUnitTest :core:diagnostics-data:testDebugUnitTest :app:testGithubDebugUnitTest --tests ... -Pripdpi.skipNativeBuild=true --no-daemon` | 146 focused tests, 0 failures across home composite, scan workflow/controller/coordinator, execution policy, archive exporter/renderer, detail/share services, history store, ViewModel, copy, and share intents |
| Retention and archive-failure focused tests | `./gradlew :core:diagnostics-data:testDebugUnitTest :core:diagnostics:testDebugUnitTest :app:testGithubDebugUnitTest --tests 'com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryStoresRoomTest' --tests 'com.poyka.ripdpi.diagnostics.DiagnosticsArchiveComponentsTest' --tests 'com.poyka.ripdpi.diagnostics.DiagnosticsBootstrapperTest' --tests 'com.poyka.ripdpi.activities.DiagnosticsArchiveCopyTest' --tests 'com.poyka.ripdpi.activities.DiagnosticsViewModelTest' -Pripdpi.skipNativeBuild=true --no-daemon` | Pass, 252 tasks, 0 failures; XML confirms history retention trims stale scan/probe/snapshot/telemetry/native-event/export/bypass/DNS rows, archive cache cleanup keeps only managed fresh archives, archive generation failure clears busy state with a negative message, and save-copy failure raises `IOException` when the destination stream cannot be opened |
| UI state and locale unit tests | `./gradlew :app:testGithubDebugUnitTest --tests ... -Pripdpi.skipNativeBuild=true --no-daemon` | 220 focused tests, 0 failures across Home, Settings, History, Diagnostics, Onboarding, shell controller, locale config, and related UI state factories/models |
| Browser, HTTP, DNS, TLS, and session native tests | `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-diagnostics-http -p ripdpi-diagnostics-tls -p ripdpi-diagnostics-dns -p ripdpi-diagnostics-classification -p ripdpi-packets -p ripdpi-session -- --nocapture` | 490 tests, 0 failures across HTTP response parsing/classification, redirects, error-page fingerprints, TLS alert/profile handling, DNS anomaly/redirect detection, packet field extraction, SSH banner classification, QUIC parsing, and session triggers |
| Kotlin strategy unit tests | `./gradlew :app:testGithubDebugUnitTest :core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest --tests ... -Pripdpi.skipNativeBuild=true --no-daemon` | 91 focused tests, 0 failures across strategy config UI, import/route flows, strategy pack logic, host-pack presets, packet-smoke network support, diagnostics strategy probe presenters/coordinator/service, recommendation invariants, and strategy pack repository/service logic |
| Native strategy and desync tests | `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-strategy-config -p ripdpi-strategy-http -p ripdpi-strategy-ipv6 -p ripdpi-strategy-lua -p ripdpi-strategy-registry -p ripdpi-strategy-trait -p ripdpi-strategy-udp -p ripdpi-strategy-window -p ripdpi-desync -p ripdpi-desync-runtime -- --nocapture` | 300 tests, 0 failures across TCP desync planning/runtime, TTL/OOB/fake ordering/fallbacks, config reload and YAML/TOML parsing, HTTP header transforms, IPv6 extension injection, Lua VM/script compile coverage, registry materialization, strategy trait contracts, UDP length mutation, and window clamp planning |
| Physical proxy smoke and host SOCKS curl | `test-lab/scripts/adb-run-probe.sh --profile device --mode proxy --timeout-ms 10000 --out-dir test-lab/artifacts/proxy-smoke-20260514-030750`; `adb forward tcp:11080 tcp:1080`; `curl --socks5-hostname 127.0.0.1:11080 http://192.168.1.9:8080/get` | Debug app launched via automation UI, local proxy service started from the Home screen, probe verdict `Degraded` only because QUIC is unsupported by the Android debug probe; proxy ready, DNS/HTTP/HTTPS/TCP/UDP pass, errors empty; host curl returned HTTP 200 through forwarded SOCKS5 |
| Physical proxy port conflict | `test-lab/artifacts/proxy-port-conflict-20260514-041518/summary.txt` plus captured `home-before.xml`, `home-after.xml`, screenshots, and `logcat-after-start.txt` | Pixel 8 Pro held port 1080 open with `toybox nc`; starting Local DPI Bypass from Home left the mode inactive, restored the `Enable` action, and showed `Failed to start Proxy: ... Address already in use (os error 98)`; native logs recorded the bind error plus `Proxy status: Disconnected -> Failed` and `Failed -> Disconnected`; cleanup confirmed no app process or listener remained |
| Physical proxy process-kill recovery | `./gradlew :app:testGithubDebugUnitTest --tests 'com.poyka.ripdpi.automation.DebugAutomationControllerTest' -Pripdpi.skipNativeBuild=true --no-daemon`; `./gradlew :app:installGithubDebug -Pripdpi.nativeAbisOverride=arm64-v8a -Pripdpi.nativeAbiParallelism=1 --no-daemon`; `test-lab/artifacts/proxy-process-kill-live-relaunch-fixed-20260514-042706/summary.txt` | Focused unit test passed; updated debug APK installed on Pixel 8 Pro; Local DPI Bypass started with UI status `Active`; killing the app process with `run-as` caused Android to restart the sticky proxy service in a new process, port 1080 remained listening, and live automation relaunch preserved UI status `Active` plus the `Disable` action; no fatal log lines; cleanup confirmed no app process or listener remained |
| API 27, 31, and 35 emulator proxy smokes | `sdkmanager --install system-images;android-27;google_apis;arm64-v8a`; repaired API 31 and API 35 images with `sdkmanager --uninstall/--install`; created `RIPDPI_API27`, `RIPDPI_API31`, and `RIPDPI_API35`; ran `adb-run-probe.sh --profile emulator --mode proxy --timeout-ms 10000` on each | API 27 / Android 8.1, API 31 / Android 12, and API 35 / Android 15 each booted from a clean emulator image, installed the debug APK, launched Home through automation, started local proxy from the UI, and passed proxy readiness plus DNS/HTTP/HTTPS/TCP/UDP; each verdict was `Degraded` only because QUIC is unsupported by the Android debug probe |
| Persian large-font forced-RTL UI smoke | API 35 emulator with `settings put system font_scale 1.5`, `settings put global debug.force_rtl 1`, `cmd locale set-app-locales com.poyka.ripdpi --user 0 --locales fa`; captured Home, Config, Diagnostics, and Settings XML/screenshots under `test-lab/artifacts/api35-fa-large-font-ui-20260514-034335/` | App locale reported `[fa]`; Home, Config, Diagnostics, and Settings root nodes plus bottom navigation nodes rendered; screenshots captured for each screen; no app fatal exception or ANR markers in the captured logcat tail |
| Installed release log verbosity | `./gradlew :app:testGithubDebugUnitTest --tests 'com.poyka.ripdpi.RipDpiAppLoggingTest' -Pripdpi.skipNativeBuild=true --no-daemon`; `./gradlew :app:assembleGithubRelease -Pripdpi.nativeAbisOverride=arm64-v8a -Pripdpi.nativeAbiParallelism=1 --no-daemon`; installed rebuilt release APK on API 35 emulator, cleared app data/logcat, launched via launcher intent, and captured `test-lab/artifacts/release-log-verbosity-20260514-040812/app-logcat.txt` | Unit test confirmed debug builds keep WorkManager verbose logging while release builds use `WARN`; release R8 strips remaining WorkManager debug/verbose calls; rebuilt release APK launched on API 35; scan found `app_owned_debug_verbose_lines=0` and `sensitive_marker_lines=0`. Broad process logcat still contains platform startup `D/V` lines outside app-owned tags |

## Checklist Section Coverage

| Checklist section | Status | Evidence | Remaining work |
| --- | --- | --- | --- |
| How to use | Partial | Changed areas identified as test-lab, root detection, diagnostics archive redaction, Android build packaging, native test stability | Full release-candidate matrix is not complete |
| Test dimensions | Partial | Debug, release verification build, physical arm64, API 27 emulator, API 31 emulator, API 34 emulator, API 35 emulator, API 36 physical, VPN, proxy, diagnostics-only, local dual-stack lab service set | Rooted physical device; cellular, handover, private DNS, IPv4-only, IPv6-only, captive or limited network |
| Core smoke matrix | Partial | Physical VPN E2E, API 27/31/35 emulator proxy smokes, physical proxy smoke with host SOCKS curl, diagnostics probe, connected tests, release APK inspection, static analysis, Roborazzi and unit gates from this pass | Full relay matrix and GitHub Actions confirmation after push |
| App shell, navigation, and settings | Partial | API 34 fresh-start UI smoke reached onboarding, Home, and Settings; API 35 Persian large-font forced-RTL smoke rendered Home, Config, Diagnostics, and Settings; connected UI tests passed with no failures; focused Home, Settings, Onboarding, shell-controller, and settings-state unit tests passed; proxy process-kill relaunch kept the UI aligned with the restarted service | Manual TalkBack, dynamic-color contrast, VPN process-kill, and migrated-install checks |
| Proxy service | Partial | Service lifecycle connected tests passed, including resolved command-line preferences; physical proxy smoke proved local SOCKS5 readiness plus DNS/HTTP/HTTPS/TCP/UDP through the lab; host `curl --socks5-hostname` returned HTTP 200 through `adb forward`; API 27/31/35 emulator proxy smokes passed the same lab probes; physical port-conflict run confirmed the app reports the bind error and returns to inactive instead of claiming readiness; physical process-kill run confirmed sticky restart keeps the listener and UI active; focused proxy supervisor, startup-failure, runtime-coordinator, auto-apply, JSON, and preference tests passed | Relay-on proxy combinations |
| VPN service | Partial | Physical VPN probe established TUN, proxy readiness, protected egress, IPv4 route, DNS/HTTP/HTTPS/TCP/UDP success; notification and permission flow instrumentation passed | Cellular handover, private DNS, IPv4-only, IPv6-only, always-on/lockdown, relay-on VPN, Unix socket protection fallback runtime path |
| DNS and resolver resilience | Partial | Diagnostics and VPN probes resolved local DNS; focused native tests cover resolver metadata, encrypted-DNS endpoint selection, DNS anomaly signals, malformed pointers, CNAME redirects, TTL divergence, and oracle quorum behavior | DoH, DoT, DNSCrypt, DoQ provider success on device; handover behavior; OS private DNS runtime validation |
| Packet strategy features | Partial | Packet-smoke instrumentation report present with 0 failures; Kotlin strategy UI/probe/service tests passed; native desync, registry, config, HTTP, IPv6, Lua, UDP, and window strategy tests passed; native packet and root-helper crates passed targeted tests; release APK inspected for debug-only test strings | Full per-family runtime matrix across IPv4, IPv6, TCP, UDP, QUIC, DTLS, Lua rawsend, and rooted privileged strategies |
| Relay and tunneling paths | Partial | Mock relay readiness verified by test-lab doctor; archive includes deterministic lab state; focused relay unit tests cover runtime config resolution, subprocess supervision, UI validation, MASQUE credentials, Cloudflare publish/geohash, and NaiveProxy policy | Production relay paths still need provider-backed runtime validation: VLESS Reality, VLESS xHTTP, WARP, Cloudflare Tunnel, MASQUE, Hysteria2, TUIC v5, ShadowTLS v3, NaiveProxy, WebTunnel, obfs4, Snowflake, Google Apps Script path |
| Diagnostics workflows | Partial | Physical diagnostics-only probe passed DNS/HTTP/HTTPS/TCP/UDP; connected diagnostics tests passed; focused home composite, scan workflow/controller/coordinator, execution-policy, ViewModel, share/copy, archive exporter, and archive renderer tests passed | Full matrix audit runtime, RAW_PATH with VPN stop behavior, and device-level cancellation/UI busy-state confirmation |
| Autolearn and remembered networks | Partial | Focused unit tests cover host filtering settings, remembered-network policy storage, and recommendation persistence invariants | Runtime handover trigger, matching network identity on a real network, reset flow, capacity pressure, schema migration, and export/import behavior |
| Browser and HTTP stack | Partial | Connected tests and parser tests present in the physical Android test report; secure HTTPS probe succeeded; focused native tests cover HTTP response parsing/classification, redirects, TLS alert/profile handling, SSH banner classification, packet field extraction, and session triggers | Browser route handoff after service restart, HTTP/2 retry eligibility, ECH platform gating on device, and parser fault isolation matrix |
| Root helper and privileged operations | Partial | Non-rooted physical degradation confirmed; API 34 rooted emulator shell showed `su 0 id` works; root detector and helper-manager unit tests passed; native IPC and privileged-operation tests passed | Rooted physical device app lifecycle, helper APK extraction through app startup, helper readiness, actual privileged send operations, helper stop cleanup |
| Logging, history, export, and privacy | Covered locally | Diagnostics archive redaction was fixed and verified; release APK omits lab/test artifacts; archive contains redacted logcat; focused diagnostics history, archive exporter/renderer, detail/share, copy, and share-intent tests passed; focused retention/archive-failure run confirmed stale diagnostics rows are trimmed, archive cache cleanup is scoped to managed archives, archive generation failure clears busy state with an error message, and save-copy failure throws `IOException`; installed release log scan found no app-owned debug/verbose lines and no sensitive markers | None for the local logging/export/privacy slice |
| UI, Compose, localization, and accessibility | Partial | Roborazzi gates passed earlier in this pass; locale key diff, locale config, and README selector checks passed; API 34 UI smoke had no fatal logcat markers; API 35 Persian large-font forced-RTL smoke captured Home, Config, Diagnostics, and Settings screenshots/XML with no app fatal logcat markers; focused Home, Diagnostics, History, Settings, Onboarding, shell, and UI-state tests passed | TalkBack, Chinese/German/Spanish compact text fit, dynamic-color contrast, and full device visual matrix |
| Test-lab and automation tooling | Partial | `doctor.sh`, `start.sh`, `stop.sh`, UDP echo, mock relay, toxiproxy delay/reset, archive redaction, shell/YAML/Compose validation all passed | Linux netem routed VM packet-loss scenario and GitHub Actions run remain unverified locally |
| CI, release, and supply chain | Partial | Local static analysis, release assemble, build logic check, native bloat check, offline analytics unit/sample pipeline, cargo-deny, packet smoke, coverage, Roborazzi, Rust workspace tests, and release APK inspection passed in this pass | Remote CodeQL, remote CI matrix, remote offline analytics workflow, mutation-testing scope confirmation |
| Runtime mode by DNS by relay | Partial | Proxy mode with lab DNS and relay-off path covered; VPN with local DNS and relay-off path covered; diagnostics-only with local DNS covered; test-lab mock relay readiness covered | Proxy plain override, proxy encrypted DNS, and relay-on device runs |
| Runtime mode by packet strategy | Partial | Instrumented packet-smoke, Kotlin strategy workflow tests, native packet tests, and native strategy/desync tests passed; QUIC unsupported is reported as degraded by Android debug probe | Runtime matrix across proxy, VPN, diagnostics, and rooted strategies |
| Relay by runtime mode | Partial | Mock relay readiness only | All production relay paths remain open |
| Network fault matrix | Partial | Toxiproxy delay and reset scenarios applied and cleared; probe reports degraded QUIC unsupported rather than stale success; proxy process-kill restart verified on device | UDP packet loss in routed VM, malformed HTTP, TLS alert, VPN process kill, handover, DNS timeout/reset across proxy/VPN/diagnostics |
| Feature definition of done | Partial | Direct tests, runtime smoke, failure injection, redaction, docs, and CI gates covered for the local test-lab/root/build-packaging fixes | Manual and provider-backed release matrix remains open |

## Bugs Found and Fixed During This Pass

| Finding | Fix commit |
| --- | --- |
| Test-lab doctor could report success when services were dead because wrapper logic suppressed failures | `c1c4eed4 fix(test-lab): fail doctor on dead services` |
| Emulator root detection missed AOSP `su 0 id` behavior | `46c2b1fb fix(service): support emulator su detection` |
| Diagnostics support archive redaction did not cover endpoint-like logcat fields | `d220cc47 fix(diagnostics): redact logcat archive endpoints` |
| Release APK included `DebugProbesKt.bin` from coroutine debug probes | `ce7e4cc6 fix(build): exclude coroutine debug probe asset` |
| Installed release app emitted WorkManager debug scheduler lines in the app-owned log scan | `bfa8cf0d fix(app): suppress release WorkManager debug logs` |
| Debug automation `SERVICE_PRESET=live` overwrote real sticky-service state to idle during process-kill relaunch verification | `0e7b312d fix(app): preserve live automation service state` |

## Current Open Gaps

These are not product pass/fail findings yet; they are missing environments or
unexecuted manual rows from `docs/feature-test-checklist.md`.

- Rooted physical device coverage for helper extraction, helper startup,
  privileged packet operations, readiness timeout, and cleanup.
- Physical cellular, Wi-Fi-to-cellular, cellular-to-Wi-Fi, private DNS,
  IPv4-only, IPv6-only, captive, and limited-path network runs.
- Full production relay provider matrix across proxy, VPN, diagnostics,
  restart, invalid credential, reset, timeout, malformed response, DNS fallback,
  and network handover.
- Manual accessibility and layout coverage for TalkBack, compact Chinese text,
  wider German/Spanish labels, and dynamic-color contrast.
- Linux routed VM netem packet-loss scenario.
- Remote GitHub Actions, CodeQL, offline analytics, and mutation workflow
  confirmation for the local commits ahead of `origin/main`.

## Next Concrete Runs

1. Run a rooted physical-device pass for root-helper startup and privileged
   operations.
2. Run a manual network pass for cellular, handover, private DNS, IPv4-only, and
   IPv6-only.
3. Execute the provider-backed relay matrix, starting with mock relay parity in
   proxy and VPN mode, then one production relay at a time.
4. Push or dispatch the branch and verify remote CI, CodeQL, offline analytics,
   and mutation workflows.
