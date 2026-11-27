---
id: TST-1795615863005942
title: Add an on-device own-server journey test
kind: chore
status: review
area: testing
priority: medium
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-26
spec_reason: test-only
---

## Goal

An automated journey proves that a new user can add, connect, observe, and disconnect their own server.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). The audit could not verify a successful relay connection or its monitoring, because it used unreachable test addresses. It also did not cover paste from a real clipboard, the Advanced persona, Proxy-only mode, file import, or subscription refresh.

## Acceptance criteria

- A Kaspresso or local-acceptance journey goes from a clean install to a connected VLESS and AmneziaWG server on a local test endpoint, checks Status, and disconnects.
- The journey covers paste link with a real clipboard and the Proxy-only mode.
- The journey runs on the local emulator pool and its result is recorded.

## Ownership

One writer in a dedicated worktree owns `app/src/androidTest/` journeys and local test fixtures.

## Evidence

The journey is `app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/OwnServerJourneyE2ETest.kt`. It starts `MainActivity` with the real service (`AutomationServicePreset.Live`) after the runner clears app data (`pm clear`), skips setup, opens Home > Add server > Paste link, and puts the server text into the real system clipboard first. It runs as two local-acceptance scenarios in `scripts/ci/run-android-local-acceptance.py` against the existing owned peers; no new endpoint fixture was added.

- `android-own-server-vless`: a VLESS REALITY link built from the `xray-provider-peer` manifest. Add > Connect starts the VPN; an HTTP request from the separate test UID through TUN reaches the peer echo (`xray-owned-echo`, peer `receipts` count +1). Status names the server `Own VLESS`; Disconnect halts the service. Connection > Proxy only, then Connect on Status starts Proxy mode; a SOCKS request through the app proxy reaches the same echo. Disconnect halts the service.
- `android-own-server-awg`: an AmneziaWG 3.1 `.conf` for the `amneziawg-peer` HTTP-TUN mode. The pasted text opens the filled editor; Connect starts the VPN; a 256 KiB download through TUN matches the peer transport receipt (id and SHA-256). Status shows the server metric; Disconnect, Connect again on Status, a second matching transfer, Disconnect. The peer wire check accepts two handshakes on `awg-3.1-ranges-v1`.

Final run, both scenarios in one pool lease, source `3811908b81`, run directory `build/acceptance/own-server-20261126181354` in the job worktree (copy of results, JUnit, receipts and instrumentation logs in the session notes folder `own-server-journey-evidence`):

```text
python3 scripts/ci/android-avd-pool.py run --api 35 --arch arm64-v8a --target google_apis_playstore --profile pixel_6 -- build/acceptance/own-server-journey.sh
Leased RIPDPI_Pool_1 as emulator-5554
PREPARE_EXIT=0
SCENARIO android-own-server-vless EXIT=0   # vlessLinkFromClipboardConnectsThroughVpnAndProxyOnly passed; echo receipts 2, direct receipts 0
SCENARIO android-own-server-awg EXIT=0     # awgConfFromClipboardConnectsThroughVpnAndReconnects passed; transport receipts 2; wire: 2 handshakes
POOL_EXIT=0
```

The script runs `run-android-local-acceptance.py --prepare` (libXray AAR built and verified with `scripts/native/build-libxray.sh --abis arm64-v8a`), then `--scenario android-own-server-vless` and `--scenario android-own-server-awg` with `--prepared-dir`.

Fixture change: a VPN connect from the UI probes `https://connectivitycheck.gstatic.com/generate_204` through the new relay before it reports Connected (`UpstreamRelaySupervisor.requireRelayEgress`). The Xray peer blackholes every public destination, so the VLESS journey failed with "Relay server could not be reached". `xray-provider-peer --connectivity-check` adds one public IPv4 route for that host on TCP 443; only the VLESS journey scenario enables it, so that scenario needs host internet access.

Findings that the journey records but does not fix:

- Navigation (fixed): Home > Add server pushed `ConnectionAddServer` without saving the Status tab state, so the Status tab did nothing on that screen and reopened it after a switch to Connection. Home > Add server now uses `navigateConnectionSubRoute`, like the other Status shortcuts into Connection; `HomeNavigationTest.statusTabLeavesAddServer` covers it, and the AWG journey returns to Status with the Status tab. After the fix both scenarios passed again in one lease on `RIPDPI_Pool_1` (run directory `build/acceptance/own-server-20261126205013`, `PREPARE_EXIT=0`, both `SCENARIO ... EXIT=0`, `POOL_EXIT=0`); `./gradlew :app:testGithubDebugUnitTest --tests 'com.poyka.ripdpi.ui.navigation.*'`, `:app:compileGithubDebugAndroidTestKotlin` and `:app:detekt` exit 0.
- Server name (fixed): Status named an AmneziaWG server (`home-server-metric`) but not an imported VLESS relay server, because `ActiveExit` covers only subscription members and AmneziaWG profiles. Status now falls back to the running native relay profile and names it as the server list does (the import name from the proxy group, else the profile id); `ActiveExit` and the Connection screen are unchanged. `MainViewModelTest` covers the name source; the VLESS journey now waits for `Own VLESS` in the server metric in VPN and Proxy mode. Runs on `RIPDPI_Pool_1`: `build/acceptance/own-server-20261127092845` (AWG passed; VLESS failed only on the journey matcher, with `Own VLESS` shown) and `build/acceptance/own-server-20261127093607` (VLESS passed, `POOL_EXIT=0`).
- DNS warning (fixed in the fixture): behind the hermetic peer, Status showed "Connected · DNS issue" for VLESS. The default public DoH resolver has no route through the peer, and the peer's own DoH at the private bootstrap address 10.0.2.2 is reached directly, not through the relay, so both lookups failed. `xray-provider-peer` now also routes its DoH port on the owned DNS address 192.0.2.53. The VLESS journey resolves through that DoH (`useXrayPeerDns`, shared with `XrayProviderE2ETest`) and checks that Status shows no DNS warning in VPN and Proxy mode. Run `build/acceptance/own-server-20261127103521` on `RIPDPI_Pool_1`: VLESS passed, no DNS failure in logcat, `POOL_EXIT=0`.
- The first tap on Connect right after Add on the import confirmation was lost once (attempt 3); the journey taps until the button accepts the tap and logs a warning when more than one tap is needed. The final run needed one tap.

Gates at `3811908b81`: `./gradlew :app:compileGithubDebugAndroidTestKotlin -Pripdpi.skipNativeBuild=true` exit 0; `./gradlew :app:detekt -Pripdpi.skipNativeBuild=true` exit 0; `just ci-gates` exit 0; `python3 -m unittest scripts.tests.test_android_local_acceptance` (32 tests) and `python3 -m unittest discover -s test-lab/acceptance/tests` (44 tests) OK; `go vet ./...` and `go build` in `scripts/fixtures/xray-provider-peer` OK.

Not covered: physical-device networking, file import (`.conf` file picker), subscription refresh, and the Advanced persona.
