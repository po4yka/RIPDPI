# Xray provider regression matrix

This is the index of the Xray-provider test surface and, for each lane, where
it runs: **CI (offline)**, **device/emulator**, **live network**, or
**private fixtures**. It exists so that before Xray is promoted to a
*recommended* fallback, every behaviour that can be locked down offline is
locked down, and every behaviour that needs real hardware/network is written
down as an explicit manual lane rather than silently skipped.

The guiding split:

- **Pure-Kotlin / Android-library lanes run in CI offline.** They cover the
  lifecycle state machine, config rendering and validation, the protect-fd
  contract, the DNS-loop invariant, telemetry redaction, and provider/handover
  selection. None of these modules build native code (no `ripdpi.android.rust-native`
  plugin), so they run on the offline toolchain.
- **Anything that touches gomobile/libXray, the NDK 29 native engine, a real
  device, or a reachable Xray server is a documented manual lane.** These
  cannot run on the offline CI toolchain (Go, gomobile, and NDK 29 are absent)
  and are marked **UNVERIFIED IN CI**.

No private endpoints appear in any fixture. All offline tests use synthetic
local fixtures (loopback addresses, `*.example` / `*.internal.example`
hostnames, and per-field synthetic secrets).

## CI (offline) lanes

Run with `./gradlew :<module>:testDebugUnitTest --offline`.

| Lane | Module | Test class | What it locks |
|------|--------|-----------|---------------|
| Provider-kind / state machine | `:core:data:runtime-state` | `VpnProviderKindTest`, `VpnProviderStateTransitionTest`, `XrayTunnelTopologyTest` | Provider kinds, the `Stopped→Starting→Running→Stopping→Stopped` table, abort edge, topology shape |
| Mode selection & diagnostics | `:core:data:runtime-state` | `XrayServiceModeOptionTest`, `XrayProviderDiagnosticsTest` | Mutually-exclusive provider×mode picker set; typed diagnostics shape (no secrets) |
| Config render (goldens) | `:core:data:catalog` | `XrayConfigRendererTest` | VLESS/REALITY and VLESS/XHTTP golden JSON; invalid combinations rejected (empty flow, `allowInsecure`, REALITY+XHTTP at broken tag); tester rejection |
| Config validation | `:core:data:catalog` | `XrayConfigValidatorTest` | `VLESS_FLOW_MISSING`, `ALLOW_INSECURE_DISABLED`, `REALITY_XHTTP_BROKEN_AT_TAG`; tag-version gating |
| Import parsing | `:core:data:catalog` | `XrayImportParserTest`, `XrayCapabilityTest` | Fail-closed `vless://` / raw-JSON import; capability labelling |
| Telemetry redaction | `:core:data:catalog` | `XrayProfileRedactorTest`, **`XrayRedactionRegressionTest`** | No UUID / REALITY key / server / SNI survives in a redacted rendered config, typed summary, validation error, or tester error |
| Runtime lifecycle | `:core:engine-api` | `RipDpiXrayRuntimeTest` | Protect-first ordering, blank-config reject, readiness success/timeout, crash→typed cause, idempotent/late/hung stop, secret-free telemetry |
| Service lifecycle matrix | `:core:engine-api` | **`XrayServiceLifecycleMatrixTest`** | End-to-end matrix: startup failure, readiness timeout, clean stop (idempotent), restart, handover, crash-fast |
| Protect-fd contract | `:core:engine-api` | **`XrayProtectFdContractTest`** | Every non-loopback outbound socket is offered to protect **before** connect; a denied protect aborts the socket; loopback inbound is never protected |
| DNS-loop regression | `:core:engine-api` | **`XrayDnsLoopRegressionTest`** | Bridged path pins DNS ownership to the tunnel; the split `XrayDns` model is not constructible; SetTunFd is refused not silently bridged |
| Provider selection & handover | `:core:engine-api` | `XrayProviderOrchestratorTest`, `XrayTunnelHandoffTest` | Native-default, Xray loopback upstream, dual restart ordering (tunnel before Xray), teardown on failure |
| Import/onboarding UX | `:app` | `XrayProfileImportScreenTest`, `XrayProfileImportViewModelTest` | Provider picker + fail-closed import surface (Robolectric; see app-module caveat below) |

> **App-module caveat.** `:app` transitively configures the native engine, so
> `:app:testGithubDebugUnitTest` does **not** run on this offline toolchain
> (NDK 29 absent). The two `:app` Xray test classes are authored and were green
> during development; treat them as **UNVERIFIED IN CI** here and gate them in
> the device/CI environment that has NDK 29.

### The four offline modules, one command each

```sh
./gradlew :core:data:runtime-state:testDebugUnitTest --offline
./gradlew :core:data:catalog:testDebugUnitTest        --offline
./gradlew :core:engine-api:testDebugUnitTest          --offline
# :app — only where NDK 29 is present:
./gradlew :app:testGithubDebugUnitTest
```

## Device / emulator lanes (UNVERIFIED IN CI)

These need a real device or emulator with the libXray AAR linked
(`XrayNativeBridgeLibXrayImpl` active, not `FakeXrayNativeBridge`) and the
NDK 29 native engine.

| Lane | Needs | Proves | Reference |
|------|-------|--------|-----------|
| TUN-bridge traffic smoke | device + libXray + reachable server | Real app traffic egresses through the Xray outbound; outbound sockets are protected so nothing loops into the TUN | [`xray-tun-bridge-smoke.md`](xray-tun-bridge-smoke.md) |
| Protect-fd on real sockets | device + libXray + `VpnService` | The *production* protect path (`VpnService.protect(int)`) is invoked for each real outbound fd before connect — the runtime half is proven offline by `XrayProtectFdContractTest`; this confirms the native wiring | [`xray-tun-bridge-smoke.md`](xray-tun-bridge-smoke.md) |
| Process-death persistence | device + `adb shell am kill` | Provider state / chosen profile is reconstructed after an LMK-style kill | per `.claude/rules/android-vpn-lifecycle.md` |
| Native artifact packaging | Go + gomobile | libXray AAR builds and matches the pinned `libXray`/`xray-core` versions | `scripts/native/build-libxray.sh`, `scripts/native/verify-libxray-artifacts.sh`, [`../native/libxray-packaging.md`](../native/libxray-packaging.md) |

## Live-network lanes (UNVERIFIED IN CI)

| Lane | Needs | Proves |
|------|-------|--------|
| Egress / leak check | device + live network + server vantage point | The connection source observed server-side is the Xray server's outbound, not the client ISP; no DNS leak outside the tunnel |
| Real REALITY/XHTTP handshake | live server with REALITY keys | The handshake the offline goldens describe actually completes against a real xray-core server at the pinned version |

## Private-fixture policy

- **No real endpoints in fixtures.** Offline tests use loopback
  (`127.0.0.1`), documentation hostnames (`*.example`,
  `*.internal.example`), and per-field synthetic secrets so a leak of any
  single field is caught by substring search.
- **Real server profiles stay out of the repo.** The device/network lanes
  above require an imported, validated profile supplied at run time by the
  operator — never committed. Validation gates the import via
  `XrayConfigValidator` before the profile is used.
- **Golden/bless discipline is unchanged.** The render goldens live in the
  unit test source, not under `tests/golden/`; the bless rules in
  `.claude/rules/golden-bless-discipline.md` are unaffected.

## Promotion checklist

Before Xray is offered as a *recommended* fallback:

1. All four offline module test tasks green (the commands above).
2. The device TUN-bridge smoke lane passed once on the target Android version,
   recorded with the libXray/xray-core versions under test.
3. The live egress/leak check confirmed server-side source and no DNS leak.
4. Process-death persistence verified via `adb shell am kill`.
5. The native artifact verified by `verifyLibXrayArtifacts` against the pins in
   `gradle/libs.versions.toml`.
