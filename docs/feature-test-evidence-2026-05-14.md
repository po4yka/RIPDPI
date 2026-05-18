# Feature Test Evidence Ledger

Status: local evidence refreshed on 2026-05-18; release sign-off is not complete.

This file keeps the path expected by `test-lab/scripts/check-feature-test-signoff.sh`, while the evidence below reflects the current 2026-05-18 local QA pass on commit `31ce8e33` with the connected non-rooted Pixel 8 Pro and local lab tooling.

## Run Metadata

| Field | Value |
| --- | --- |
| Date/time | 2026-05-18, Asia/Tbilisi |
| Operator | Codex local QA agent |
| Git commit | `31ce8e33` after local QA fixes |
| Device | Pixel 8 Pro `38080DLJG000GX`, Android 16 / API 36, non-rooted |
| Local lab host | macOS Darwin host `po4ykas-MacBook-Pro.local`; device-profile lab endpoints on `192.168.1.9` |
| Readiness artifact | `test-lab/artifacts/feature-gap-readiness-20260518-final.json` |
| Appium report | `appium/appium-report.html` |
| Proxy E2E artifact | `test-lab/artifacts/proxy-e2e-20260518-181143/probe-device-proxy.json` |
| VPN E2E artifact | `test-lab/artifacts/vpn-e2e-20260518-181338/probe-device-vpn.json` |
| Adversarial dry-run artifact | `test-lab/artifacts/adversarial-middlebox-dryrun-20260518/verdict-report.json` |
| Netem capability artifact | `test-lab/artifacts/netem-container-capability-20260518.txt` |

## Command Evidence

| Command or artifact | Result | Notes |
| --- | --- | --- |
| `adb devices -l` | Ready | Connected Pixel 8 Pro `38080DLJG000GX`. |
| `./gradlew :app:testGithubDebugUnitTest -Pripdpi.skipNativeBuild=true` | Passed | Full app unit lane passed after fixing the local-mode stop policy and Compose assertion issues. |
| `ANDROID_SERIAL=38080DLJG000GX ./test-lab/scripts/run-proxy-e2e.sh --profile device --keep-lab --timeout-ms 12000` | Passed with degraded UDP probe | Proxy connect/disconnect passed; DNS, HTTP, HTTPS, TCP passed; Docker Desktop UDP from physical Wi-Fi timed out and remains classified recoverable. |
| `ANDROID_SERIAL=38080DLJG000GX ./test-lab/scripts/run-vpn-e2e.sh --profile device --keep-lab --timeout-ms 12000` | Passed with degraded UDP probe | VPN consent/start, TUN, protected egress, IPv4 route, proxy and relay readiness, DNS, HTTP, HTTPS, TCP, and disconnect cleanup passed; Docker Desktop UDP from physical Wi-Fi timed out and remains classified recoverable. |
| `./gradlew :app:connectedGithubDebugAndroidTest -Pripdpi.localNativeAbis=arm64-v8a -Pandroid.testInstrumentationRunnerArguments.package=com.poyka.ripdpi.e2e` | Passed | Physical Pixel E2E package finished 35/35 tests with zero failures. |
| `ANDROID_SERIAL=38080DLJG000GX bash scripts/ci/run-android-packet-smoke.sh` | Passed | Non-rooted physical proxy packet-family smoke passed; raw-capture VPN lanes were skipped as expected for this device class. |
| `ANDROID_SERIAL=38080DLJG000GX RUNNER_TEMP=/tmp/ripdpi-appium-20260518 bash scripts/ci/run-appium-smoke.sh` | Passed | Full Appium smoke rerun after fixes: 78 passed, 18 skipped, 1 warning, 0 failures. |
| Adversarial middlebox dry-run script with output directory `test-lab/artifacts/adversarial-middlebox-dryrun-20260518` | Passed | 53 Python tests passed; dry-run matrix produced 63 cells with totals `blocked=23`, `pass=40`, `degraded=0`, `inconclusive=0`. |
| `docker exec ripdpi-linux-netem ... tc qdisc replace dev eth0 root netem loss 1%` | Passed as container capability check | The Linux container has `tc`, `nft`, `CAP_NET_ADMIN`, and `CAP_NET_RAW`; loss applied and cleared on `eth0`. This does not prove routed Pixel traffic through netem. |
| `./test-lab/scripts/check-feature-test-signoff.sh --readiness test-lab/artifacts/feature-gap-readiness-20260518-final.json` | Blocked | Guard correctly blocks release sign-off while rooted physical, TalkBack, cellular handover with IPv4 and IPv6 coverage, routed netem, relay provider, and remote workflow evidence are incomplete. |
| `gh workflow run ci.yml --ref main` | Not run locally | Remote workflow evidence still required after review branch publication. |
| `gh workflow run local-network-lab.yml --ref main -f run_vpn_emulator_lane=false` | Not run locally | Remote local-network-lab workflow evidence still required after review branch publication. |
| `gh workflow run offline-analytics.yml --ref main -f private_corpus_path=''` | Not run locally | Remote offline analytics workflow evidence still required after review branch publication. |
| `gh workflow run mutation-testing.yml --ref main -f packages='' -f in_diff=false` | Not run locally | Remote mutation-testing workflow evidence still required after review branch publication. |
| `gh workflow run fuzz-nightly.yml --ref main -f fuzz_seconds=1800` | Not run locally | Remote Fuzz Nightly workflow evidence still required after review branch publication. |
| CodeQL push workflow | Not run locally | CodeQL evidence is push-triggered remote workflow evidence and still required. |

## Checklist Section Coverage

| Checklist area | Local evidence | Result |
| --- | --- | --- |
| Startup and navigation | Appium cold launch, tab navigation, settings navigation, physical instrumentation E2E | Covered locally |
| Permissions and service lifecycle | Appium permission handling, Maestro proxy/VPN E2E, foreground service cleanup assertion in VPN E2E | Covered locally for non-rooted Pixel |
| Proxy service | Maestro proxy E2E, Appium connected proxy state, packet-smoke proxy families | Covered locally with Docker Desktop UDP limitation noted |
| VPN service | Maestro VPN E2E, Appium connected VPN state, physical instrumentation E2E | Covered locally for non-rooted Pixel; cellular handover and IPv4/IPv6 matrix remain manual |
| Diagnostics flow | Appium diagnostics actions, scan run/results, strategy audit report, physical instrumentation E2E, adversarial dry-run | Covered locally for available seeded and physical flows |
| Packet strategies | Android packet-smoke proxy families and adversarial dry-run matrix | Covered locally for non-rooted proxy families; rooted-only actions require rooted physical evidence |
| Settings and persistence | Appium settings, DNS roundtrip, config edit validation, DataStore/unit tests | Covered locally |
| Logging/export | Appium support bundle and diagnostics share/save actions, archive unit tests in previously green app unit lane | Covered locally for UI and unit paths |
| Localization/accessibility | Existing locale/UI artifacts and unit/lint coverage are present, but TalkBack active-session evidence is missing | Not release-complete |
| Relay paths | Mock/local relay readiness passed in device-profile E2E; production relay provider matrix is not configured | Not release-complete |
| CI release gates | Local unit/Appium/Maestro/adversarial checks passed after fixes; CI, CodeQL, local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly remote workflow evidence is missing | Not release-complete |

## Issues Found and Fixed

| Commit | Finding | Verification |
| --- | --- | --- |
| `8325bf97` | Maestro E2E was blocked by Android notification permission on fresh debug installs. | Proxy E2E passed on Pixel. |
| `e3366777` | VPN-local sessions started from the remote card could not be stopped from the visible local-mode active card. | Focused unit test and VPN E2E disconnect passed with no foreground service leak. |
| `8b9872da` | Restoring the VPN-local stop policy regressed proxy local-mode stop behavior. | Full app unit lane passed. |
| `293613a3` | Privacy/disclosure Compose tests used stale or overly broad text selectors. | Focused and full app unit lanes passed. |
| `fb9d8b00` | The adversarial dry-run script wrote relative artifact outputs under a different working directory and then checked the wrong path. | Relative artifact dry-run passed and produced `verdict-report.json`. |
| `31ce8e33` | Appium strategy-winner test asserted a child action before scrolling it into the viewport. | Focused test, strategy-audit file, and full Appium smoke passed. |

## Current Open Gaps

| Gap | Current evidence | Required next evidence |
| --- | --- | --- |
| Rooted physical behavior | Readiness says `rooted_physical_device` is blocked because the attached Pixel did not provide root through `su 0 id`. | Run the rooted physical device section from the manual evidence template on a device with root; rooted emulator evidence can supplement local debugging but does not close the rooted physical requirement. |
| TalkBack | Readiness says TalkBack is installed but not active; active accessibility service is Bitwarden. | Enable TalkBack and record the TalkBack control-label pass. |
| Physical network matrix | Readiness says Wi-Fi and cellular transports are both visible, but handover is still manual; IPv4 and IPv6 routed coverage is not complete. | Perform cellular handover, Wi-Fi return, IPv4-only, IPv6-only, private-DNS, and limited-path runs and attach probe JSON. |
| Routed netem | The `ripdpi-linux-netem` container can apply and clear `tc netem`, but the Pixel is not proven to route through it. | Place the Linux VM/router namespace in the Pixel traffic path, then run routed netem VPN and diagnostics probes under loss and QUIC-drop scenarios. |
| Relay provider matrix | Readiness says `RIPDPI_RELAY_MATRIX_CONFIG` is unset. | Provide an operator-owned relay provider matrix and run proxy, VPN, diagnostics, restart, invalid credential, reset, timeout, malformed response, DNS fallback, and handover rows. |
| Remote workflow confirmation | Local branch is ahead of `origin/main`; remote workflow evidence is unavailable. | Publish a review branch, complete pull request review, then confirm CI, CodeQL, local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly remote workflow results. |

## Next Concrete Runs

1. Run rooted physical device checks on a rooted phone: root access detection, root-mode gating, helper extraction/readiness, privileged action artifact, cleanup, and log redaction.
2. Enable TalkBack and execute the TalkBack route pass for buttons, switches, tabs, progress, errors, and reachability.
3. Run the physical network matrix with cellular handover, Wi-Fi return, IPv4-only, IPv6-only, private-DNS, and limited-path evidence.
4. Put the Pixel traffic path through the routed netem VM/router and run packet-loss plus QUIC-drop VPN and diagnostics probes.
5. Provide the relay provider matrix and run all relay provider rows across proxy, VPN, diagnostics, restart, invalid credentials, reset, timeout, malformed response, DNS fallback, and handover.
6. Publish the branch and collect remote workflow evidence for CI, CodeQL, local-network-lab, offline analytics, mutation-testing, and Fuzz Nightly.
