# Feature Test Evidence Ledger

Status: local evidence refreshed on 2026-05-19; release sign-off is not complete.

This file keeps the path expected by `test-lab/scripts/check-feature-test-signoff.sh`, while the evidence below reflects the 2026-05-19 local QA pass and continuation checks through commit `e6a57057`. Earlier physical-device artifacts were collected while the non-rooted Pixel 8 Pro was attached; the latest continuation re-check found no Pixel or other adb device visible through adb or USB, so physical-only rows were not refreshed. The `RIPDPI_Root_API34` AVD is now Magisk-rooted with app-granting `su`, the opt-in root-helper smoke passes there, and the rooted physical-device release row remains open because the Pixel evidence is intentionally non-rooted and a rooted physical device is still unavailable.

## Run Metadata

| Field | Value |
| --- | --- |
| Date/time | 2026-05-19, Asia/Tbilisi |
| Operator | Codex local QA agent |
| Git commit | `e6a57057` after local QA and CI/test-lab fixes |
| Device | Pixel 8 Pro `38080DLJG000GX`, Android 16 / API 36, non-rooted |
| Local lab host | macOS Darwin host `po4ykas-MacBook-Pro.local`; device-profile lab endpoints on `192.168.1.9` |
| Readiness artifact | `test-lab/artifacts/feature-gap-readiness-20260519-netem-aware.json` |
| Appium report | `appium/appium-report.html` |
| Proxy E2E artifact | `test-lab/artifacts/proxy-e2e-20260519-015247/probe-device-proxy.json` |
| VPN E2E artifact | `test-lab/artifacts/vpn-e2e-20260519-015119/probe-device-vpn.json` |
| Android packet-smoke artifact | `test-lab/artifacts/android-packet-smoke-pixel8pro-full-after-autolearn-fixture-20260519` |
| Adversarial middlebox dry-run artifact | `test-lab/artifacts/tspu-dryrun-current/verdict-report.json` |
| Netem capability artifact | `test-lab/artifacts/netem-container-capability-20260519-current.txt` |

## Command Evidence

| Command or artifact | Result | Notes |
| --- | --- | --- |
| `adb devices -l` | Ready | Connected Pixel 8 Pro `38080DLJG000GX`. |
| `./gradlew :app:testGithubDebugUnitTest -Pripdpi.skipNativeBuild=true` | Passed | Full app unit lane passed after fixing the local-mode stop policy and Compose assertion issues. |
| `ANDROID_SERIAL=38080DLJG000GX ./test-lab/scripts/run-proxy-e2e.sh --profile device --skip-start --skip-install --keep-lab --timeout-ms 12000` | Passed with expected degraded QUIC probe | Proxy connect/disconnect passed; fixed loopback proxy readiness, DNS, HTTP, HTTPS, TCP, UDP, relay readiness, and service cleanup passed. The verdict is `Degraded` only because Android debug QUIC probing is explicitly unsupported and `errors=[]`. |
| `ANDROID_SERIAL=38080DLJG000GX ./test-lab/scripts/run-vpn-e2e.sh --profile device --skip-start --skip-install --keep-lab --timeout-ms 12000` | Passed with expected degraded QUIC probe | VPN consent/start, TUN, protected egress, IPv4 route, DNS, HTTP, HTTPS, TCP, UDP, relay readiness, and disconnect cleanup passed. VPN mode no longer requires the fixed `127.0.0.1:1080` listener because the service may use an ephemeral authenticated internal SOCKS hop; `errors=[]`. |
| `./gradlew :app:connectedGithubDebugAndroidTest -Pripdpi.localNativeAbis=arm64-v8a -Pandroid.testInstrumentationRunnerArguments.package=com.poyka.ripdpi.e2e` | Passed | Physical Pixel E2E package finished 35/35 tests with zero failures. |
| `ANDROID_SERIAL=38080DLJG000GX RIPDPI_PACKET_SMOKE_ARTIFACT_DIR=test-lab/artifacts/android-packet-smoke-pixel8pro-full-after-autolearn-fixture-20260519 bash scripts/ci/run-android-packet-smoke.sh` | Passed | Non-rooted physical packet-smoke passed 17/17 rows: proxy packet families, VPN baseline, DoH/DoT/DNSCrypt/DoQ success and fault rows, host autolearn, remembered policy, and ws tunnel fallback. |
| `./gradlew :core:service:testDebugUnitTest --tests com.poyka.ripdpi.services.RootHelperManagerTest --rerun-tasks --no-build-cache -Pkotlin.incremental=false -Pripdpi.skipNativeBuild=true --console=plain` | Passed | Root-helper manager unit coverage verifies fallback to the next launcher when the first `su` launch throws, plus existing readiness and cleanup behavior. |
| `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedGithubDebugAndroidTest --console=plain -Pripdpi.localNativeAbis=arm64-v8a -Pandroid.testInstrumentationRunnerArguments.class=com.poyka.ripdpi.integration.RootHelperInstrumentedTest -Pandroid.testInstrumentationRunnerArguments.ripdpi.rootHelperSmoke=true` | Passed | The `RIPDPI_Root_API34` Google APIs AVD is Magisk-rooted with app UID `su` grant. The smoke verified helper extraction, socket readiness, app-to-helper connection, stop cleanup, and no remaining `ripdpi-root-helper` process or `root_helper.sock` file after the run. |
| `ANDROID_SERIAL=38080DLJG000GX RUNNER_TEMP=/tmp/ripdpi-appium-20260518 bash scripts/ci/run-appium-smoke.sh` | Passed | Full Appium smoke rerun after fixes: 78 passed, 18 skipped, 1 warning, 0 failures. |
| `/usr/bin/python3 -m runner.cli dry-run --matrix matrix.json --fixtures fixtures --out-dir test-lab/artifacts/tspu-dryrun-current` from the adversarial middlebox harness | Passed | Dry-run matrix produced 63 cells and a complete `verdict-report.json`: 23 blocked, 40 bypassed, 0 degraded, 0 inconclusive. |
| `/usr/bin/python3 -m unittest discover -s tests` from the adversarial middlebox harness | Passed | Adversarial middlebox emulator unit suite ran 53 tests with zero failures. |
| `ANDROID_SERIAL=38080DLJG000GX ./test-lab/scripts/check-feature-gap-readiness.sh --output test-lab/artifacts/feature-gap-readiness-20260519-netem-aware.json` | Passed | Current-head readiness selects the connected physical Pixel for the Android device row, reports the running `ripdpi-linux-netem` container as a manual routed-netem prerequisite, and leaves rooted physical, TalkBack, handover, relay provider, and remote workflow rows blocked/manual. |
| `adb devices -l` and USB probe during the continuation at `e6a57057` | Blocked for physical rows | After the rooted emulator was stopped, adb listed no attached devices and `system_profiler SPUSBDataType` matched no Pixel/Google/Android/ADB device, so physical Pixel, TalkBack, and handover rows could not be refreshed in the continuation turn. |
| `ANDROID_SERIAL=emulator-5554 RIPDPI_LOCAL_NATIVE_ABI=arm64-v8a RIPDPI_PACKET_SMOKE_CAPTURE_MODE=indirect RIPDPI_PACKET_SMOKE_ARTIFACT_DIR=/tmp/ripdpi-packet-smoke-current bash scripts/ci/run-android-packet-smoke.sh` | Passed | Rooted-emulator packet smoke produced 17 scenario artifact folders with `BUILD SUCCESSFUL`; proxy hostfake/fakedsplit/fakeddisorder rows were assumption-skipped in indirect capture mode while TLS record, TLS random record, VPN baseline, encrypted-DNS success/fault, host autolearn, remembered-policy, and ws tunnel fallback rows completed. |
| `RIPDPI_TSPU_ARTIFACT_DIR=/tmp/ripdpi-tspu-current bash scripts/ci/run-tspu-dryrun.sh` | Passed | Current continuation dry-run succeeded with `verdict-report.json` totals: 23 blocked, 40 bypassed, 0 degraded, 0 inconclusive. |
| `bash test-lab/scripts/check-relay-matrix-config.sh --config test-lab/relay/provider-matrix.example.json` | Passed | Example relay matrix schema remains valid with 14 relays, 14 required paths, and 10 required scenarios; this does not replace operator production relay evidence. |
| `gh pr view 117 --json statusCheckRollup` | Passed for PR checks | PR #117 at `e6a57057` is open as a draft and the check rollup is green, including CI, CodeQL, Android network E2E, release verification, static analysis, Android instrumentation, Rust, coverage, relay interoperability, TSPU, and packet smoke. Merge-to-main and scheduled/manual workflow confirmation remain open. |
| `docker exec ripdpi-linux-netem ... tc qdisc replace dev eth0 root netem loss 1%` | Passed as container capability check | The Linux container has `tc`, `nft`, `CAP_NET_ADMIN`, and `CAP_NET_RAW`; loss applied and cleared on `eth0` in `test-lab/artifacts/netem-container-capability-20260519-current.txt`. This does not prove routed Pixel traffic through netem. |
| `ANDROID_SERIAL=38080DLJG000GX ./test-lab/scripts/check-feature-test-signoff.sh --readiness test-lab/artifacts/feature-gap-readiness-20260519-netem-aware.json` | Blocked | Guard correctly blocks release sign-off while rooted physical, TalkBack, cellular handover with IPv4 and IPv6 coverage, routed netem path proof, relay provider, and remote workflow evidence are incomplete. |
| `gh workflow run ci.yml --ref main` | Not run locally | Remote workflow evidence still required after review branch publication. |
| `gh workflow run local-network-lab.yml --ref main -f run_vpn_emulator_lane=false` | Not run locally | Remote local-network-lab workflow evidence still required after review branch publication. |
| `gh workflow run offline-analytics.yml --ref main -f private_corpus_path=''` | Not run locally | Remote offline analytics workflow evidence still required after review branch publication. |
| `gh workflow run mutation-testing.yml --ref main -f packages='' -f in_diff=false` | Not run locally | Remote mutation-testing workflow evidence still required after review branch publication. |
| `gh workflow run fuzz-nightly.yml --ref main -f fuzz_seconds=1800` | Not run locally | Remote Fuzz Nightly workflow evidence still required after review branch publication. |
| CodeQL push workflow | Not run locally | CodeQL evidence is push-triggered remote workflow evidence and still required. |
| Checklist section coverage audit | Passed | 22 checklist sections, 248 checklist items, 22 evidence rows mapped below. |

## Checklist Section Coverage

| Checklist section | Status | Evidence | Remaining work |
| --- | --- | --- | --- |
| How to Use | Covered locally | Evidence rows name artifacts, commands, commits, and blockers for the local pass. | None for local evidence recording; release-only rows stay open below. |
| Test Dimensions | Partial | Current pass covers debug physical arm64, rooted emulator presence, local fixture DNS modes, proxy/VPN runtime modes, adversarial middlebox dry-run, and persisted settings flows. | Rooted physical, TalkBack, cellular handover, IPv4-only, IPv6-only, routed netem, production relay, and remote workflow dimensions remain. |
| Core Smoke Matrix | Partial | App unit lane, Maestro proxy/VPN E2E, Appium smoke, physical instrumentation E2E, and full physical Android packet smoke passed. | Rooted physical, cellular handover, IPv4/IPv6, production relay, routed netem, and remote workflow evidence remain. |
| App Shell, Navigation, and Settings | Partial | Appium cold launch, tab navigation, settings navigation, config validation, and physical E2E navigation passed. | TalkBack active-session, RTL visual fit, large-font release pass, and remote UI workflow evidence remain. |
| Proxy Service | Covered locally | Maestro proxy E2E, Appium connected proxy state, local proxy cleanup, and proxy packet-smoke families passed. | None for the local non-rooted Pixel proxy slice; production relay provider coverage is tracked under relay rows. |
| VPN Service | Partial | Maestro VPN E2E, Appium connected VPN state, physical instrumentation E2E, and physical VPN packet-smoke rows passed. | Cellular handover, IPv4-only, IPv6-only, private-DNS, limited-path, rooted physical, routed netem, and production relay evidence remain. |
| DNS and Resolver Resilience | Covered locally | Physical packet smoke covered DoH, DoT, DNSCrypt, DoQ, success and fault rows, plus fixture-root trust and resolver-endpoint telemetry. | None for local encrypted-DNS smoke; physical network variation remains under network matrix rows. |
| Packet Strategy Features | Partial | Android packet smoke covered proxy strategy families and VPN tunnel behavior, and the adversarial middlebox dry-run produced 63 pcap cells. | Root-only FakeRst, MultiDisorder, IpFrag2, SeqOverlap, routed netem, IPv4/IPv6, and generator/real-provider release rows remain. |
| Relay and Tunneling Paths | Partial | Mock/local relay readiness passed in device-profile E2E and ws tunnel fallback passed in physical packet smoke. | Operator-provided production relay matrix and provider-backed proxy/VPN/diagnostics/restart/fault/handover rows remain. |
| Diagnostics Workflows | Partial | Appium diagnostics actions, scan results, strategy audit report, physical instrumentation E2E, and adversarial middlebox dry-run passed. | Routed netem diagnostics, cellular handover diagnostics, production relay diagnostics, TalkBack diagnostics, and remote workflow evidence remain. |
| Autolearn and Remembered Networks | Covered locally | Physical packet smoke passed host autolearn and remembered-policy rows using fixture DNS and app-process traffic probes. | None for local non-rooted Pixel autolearn/remembered-policy smoke. |
| Browser and HTTP Stack | Covered locally | Appium browser/HTTP-stack flows and support-bundle actions passed in the local smoke set. | None for local UI/browser smoke; remote workflow evidence remains under CI rows. |
| Root Helper and Privileged Operations | Partial | Root-helper manager fallback and shutdown unit coverage passed, and the opt-in rooted instrumentation smoke passed on the app-granting Magisk `RIPDPI_Root_API34` AVD with no helper leak after cleanup. Readiness correctly reports the physical Pixel as non-rooted. | Rooted physical device evidence remains unless the app-granting rooted-emulator substitution is explicitly accepted for the release row; privileged packet-action and redaction evidence should still be expanded beyond helper lifecycle. |
| Logging, History, Export, and Privacy | Covered locally | Appium support bundle and diagnostics share/save actions passed, and archive/redaction unit coverage was included in the green unit lane. | None for local UI/export smoke; release remote workflows remain under CI rows. |
| UI, Compose, Localization, and Accessibility | Partial | Appium UI flows, locale/lint-backed resource coverage, and existing Compose tests passed in local gates. | TalkBack active-session evidence, large-font/RTL visual pass, and remote UI workflow evidence remain. |
| Test-Lab and Automation Tooling | Partial | Readiness, signoff guard, artifact path checks, Appium, Maestro, physical packet smoke, rooted-emulator packet smoke, relay example validation, and adversarial middlebox dry-run all executed locally. | Routed netem VM/router carrying Pixel traffic and remote local-network-lab workflow evidence remain. |
| CI, Release, and Supply Chain | Partial | Local hooks, unit tests, script tests, native checks, evidence guardrails, and PR #117 green check rollup passed for committed fixes through `e6a57057`. | Merge-to-main confirmation plus local-network-lab, offline analytics, mutation-testing, Fuzz Nightly, and final release workflow evidence remain. |
| Runtime Mode by DNS by Relay | Partial | Proxy/VPN local modes and encrypted-DNS resolver combinations were exercised without production relay. | Production relay matrix across runtime modes and DNS fallback rows remains. |
| Runtime Mode by Packet Strategy | Partial | Proxy packet strategy families and VPN tunnel packet-smoke rows passed on the non-rooted Pixel. | Root-only strategies, IPv4/IPv6 variants, and routed adversarial-network rows remain. |
| Relay by Runtime Mode | Partial | Mock/local relay readiness and ws tunnel fallback passed in local device-profile and packet-smoke rows. | Provider-backed relay rows for proxy, VPN, diagnostics, restart, invalid credentials, reset, timeout, malformed response, DNS fallback, and handover remain. |
| Network Fault Matrix | Partial | Adversarial middlebox dry-run and netem container capability passed. | Routed netem Pixel path, cellular handover, IPv4-only, IPv6-only, private DNS, limited path, and QUIC-drop live-path evidence remain. |
| Feature Definition of Done | Partial | Local artifacts, issue-fix commits, readiness, and signoff guard evidence are recorded. | Required readiness rows and remote workflow evidence must be ready before release sign-off. |

## Issues Found and Fixed

| Commit | Finding | Verification |
| --- | --- | --- |
| `8325bf97` | Maestro E2E was blocked by Android notification permission on fresh debug installs. | Proxy E2E passed on Pixel. |
| `e3366777` | VPN-local sessions started from the remote card could not be stopped from the visible local-mode active card. | Focused unit test and VPN E2E disconnect passed with no foreground service leak. |
| `8b9872da` | Restoring the VPN-local stop policy regressed proxy local-mode stop behavior. | Full app unit lane passed. |
| `293613a3` | Privacy/disclosure Compose tests used stale or overly broad text selectors. | Focused and full app unit lanes passed. |
| `fb9d8b00` | The adversarial dry-run script wrote relative artifact outputs under a different working directory and then checked the wrong path. | Relative artifact dry-run passed and produced `verdict-report.json`. |
| `31ce8e33` | Appium strategy-winner test asserted a child action before scrolling it into the viewport. | Focused test, strategy-audit file, and full Appium smoke passed. |
| `3d4a94ff` | Encrypted-DNS resolver sockets inside VPN mode were not protected before opening upstream connections, and fault telemetry lost the resolver endpoint label. | `ripdpi-tunnel-core` DNS-intercept tests, native architecture health, and physical DoT fault packet smoke passed. |
| `fbbd5a36` | Physical non-rooted packet smoke could not reliably exercise VPN rows because instrumentation-process probes bypassed the app VPN and stale androidTest classes could be installed. | Full physical Pixel packet smoke passed all 17 rows in `test-lab/artifacts/android-packet-smoke-pixel8pro-full-after-autolearn-fixture-20260519`. |
| `c1ce7a8e` | Feature-gap readiness falsely reported no Android device when the Pixel and rooted emulator were both attached. | `test-lab/scripts/test-feature-gap-readiness.sh` passed and clean-tree readiness marked `android_device` ready without `ANDROID_SERIAL`. |
| `1f3a5623` | Root-helper launch stopped after the first `su` launcher threw, and the new rooted smoke needed to avoid touching shell-only AOSP emulator `su` unless app-granting root is explicitly requested. | Forced `RootHelperManagerTest` passed, and `RootHelperInstrumentedTest` passed with a deliberate skip on the clean shell-rooted API 34 AVD. |
| `90c1946c` | macOS Docker Desktop returned UDP/DNS replies from an unusable source path for physical Wi-Fi device-profile probes. | Host UDP echo and host DNS helpers passed locally; Pixel proxy/VPN/diagnostics probes now show UDP and DNS success. |
| `47ea6211` | VPN E2E required the fixed debug proxy listener even though VPN mode may use an ephemeral authenticated internal SOCKS hop. | Focused debug-probe unit tests passed, and Pixel VPN/proxy E2E passed with explicit mode-specific readiness preconditions. |
| `4488a7a6` | Root-helper instrumentation hid the exact app-context `su` failure behind a generic skip, making rooted-emulator readiness ambiguous. | `:app:ktlintCheck`, `git diff --check`, and a fresh no-build-cache `RootHelperInstrumentedTest` run passed; the rooted AVD artifact now reports `error=13, Permission denied` for app-context `su` attempts. |
| `ded3ac55` | Feature-gap readiness reported a generic Darwin routed-netem blocker even when the Docker netem container was running with `tc`, hiding the actual missing evidence. | `test-feature-gap-readiness.sh`, `test-feature-test-signoff.sh`, `bash -n`, and current-head readiness passed; `routed_netem_vm` now reports manual route-proof work with the running `ripdpi-linux-netem` container. |
| `af4891d5` | The root helper bound its socket as `root:root` with an Android SELinux label that the app could not use, then leaked detached helper children when Magisk separated the root process from the `su` launcher. | `RootHelperManagerTest`, `cargo test -p ripdpi-root-helper`, `cargo clippy -p ripdpi-root-helper --all-targets -- -D warnings`, `:core:service:ktlintCheck`, `:core:service:detekt`, architecture health, and the opt-in `RootHelperInstrumentedTest` on the Magisk-rooted AVD passed; post-run process inspection found no `ripdpi-root-helper` process or socket file. |
| `e6a57057` | The evidence ledger lagged the current PR head after the Android network E2E, mutation harness, diagnostics gating, and TSPU dry-run stabilization commits landed. | Current continuation checks verified no physical adb/USB device was available, lab doctor passed 10/0/1, rooted-emulator Android packet smoke passed in indirect mode, TSPU dry-run passed, relay example schema validation passed, and PR #117 checks were green at `e6a57057`. |

## Current Open Gaps

| Gap | Current evidence | Required next evidence |
| --- | --- | --- |
| Rooted physical behavior | Readiness says `rooted_physical_device` is blocked because the attached Pixel did not provide root through `su 0 id`; `RIPDPI_Root_API34` now provides app-granting Magisk root and the opt-in root-helper lifecycle smoke passes there. | Run the rooted physical device section from the manual evidence template on a device with app-granting root, or explicitly accept the rooted-emulator substitution for the release row and add privileged packet-action evidence beyond helper lifecycle. |
| TalkBack | Readiness says TalkBack is installed but not active; active accessibility service is Bitwarden. | Enable TalkBack and record the TalkBack control-label pass. |
| Physical network matrix | Readiness says Wi-Fi and cellular transports are both visible, but handover is still manual; IPv4 and IPv6 routed coverage is not complete. | Perform cellular handover, Wi-Fi return, IPv4-only, IPv6-only, private-DNS, and limited-path runs and attach probe JSON. |
| Routed netem | Readiness now sees the running `ripdpi-linux-netem` container and marks `routed_netem_vm` manual, but the Pixel is not proven to route through that container or another Linux router namespace. | Place the Linux VM/router namespace in the Pixel traffic path, then run routed netem VPN and diagnostics probes under loss and QUIC-drop scenarios. |
| Relay provider matrix | Readiness says `RIPDPI_RELAY_MATRIX_CONFIG` is unset. | Provide an operator-owned relay provider matrix and run proxy, VPN, diagnostics, restart, invalid credential, reset, timeout, malformed response, DNS fallback, and handover rows. |
| Remote workflow confirmation | PR #117 at `e6a57057` has a green check rollup for CI, CodeQL, Android network E2E, release verification, static analysis, Android instrumentation, Rust, coverage, relay interoperability, TSPU, and packet smoke, but it is still open as a draft and not merged to `main`. | Complete review, merge to `main`, and confirm fresh main-branch CI plus local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly workflow results where required. |

## Next Concrete Runs

1. Run rooted physical device checks on a rooted phone, or explicitly accept the app-granting Magisk `RIPDPI_Root_API34` emulator substitution and add privileged packet-action artifacts plus log redaction evidence.
2. Enable TalkBack and execute the TalkBack route pass for buttons, switches, tabs, progress, errors, and reachability.
3. Run the physical network matrix with cellular handover, Wi-Fi return, IPv4-only, IPv6-only, private-DNS, and limited-path evidence.
4. Put the Pixel traffic path through the routed netem VM/router and run packet-loss plus QUIC-drop VPN and diagnostics probes.
5. Provide the relay provider matrix and run all relay provider rows across proxy, VPN, diagnostics, restart, invalid credentials, reset, timeout, malformed response, DNS fallback, and handover.
6. Complete PR #117 review, merge the branch, and collect main-branch remote workflow evidence for CI, CodeQL, local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly.
