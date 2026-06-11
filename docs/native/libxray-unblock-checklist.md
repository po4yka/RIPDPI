# libXray packaging — unblock checklist

**Status:** the entire Kotlin/Gradle substrate for Xray provider mode is landed and
offline-test-verified (121 Xray unit tests green across `:core:data:catalog`,
`:core:data:runtime-state`, `:core:engine-api`). The epic and its native-dependent
children stay **blocked on a single external gate**: no real per-ABI libXray `.aar`
has ever been produced or linked, because the build host lacks the gomobile/Go
toolchain and NDK 29. This file is the concrete, ordered checklist to clear that
gate. It does **not** authorise hand-placing a stub artifact — the verify gate
rejects any artifact whose manifest does not match the pins.

See also: [`libxray-packaging.md`](libxray-packaging.md) (the full runbook, licence
capture, and size budget) and the parent issue
[`../tasks/issues/package-libxray-for-android-abis.md`](../tasks/issues/package-libxray-for-android-abis.md).

## What is already in the tree (no work needed)

| Asset | Location | State |
| --- | --- | --- |
| Version pins (stable + canary) | `gradle/libs.versions.toml` (`libxray = "v26.3.27"`, `xray-core = "1.260327.0"`, `gomobile = "0.0.0-20260529142300-ecb4cd65260a"`, `libxray-canary = "main"`, `xray-core-canary = "main"`) | done |
| Stable-vs-canary policy + licence/NOTICE capture | `docs/native/libxray-packaging.md` | done |
| Reproducible build script (fail-closed) | `scripts/native/build-libxray.sh` | done — refuses to run without Go+gomobile+NDK; reads ABI/SDK/NDK only from `gradle.properties` |
| amd64 container recipe | `scripts/native/libxray-build.Dockerfile` | done |
| Pure-shell verify gate | `scripts/native/verify-libxray-artifacts.sh` | done — smoke-tested green against absent / missing-ABI / drift / oversize / canary-ship / valid states |
| Gradle verify seam | `core/engine/build.gradle.kts` → `:core:engine:verifyLibXrayArtifacts` (`Exec`, over `ripdpi.prebuiltXrayAarDir`, default `native/xray/artifacts/`, **not** wired into `assemble`) | done — never yet consumed a real artifact |
| Artifact directory | `native/xray/artifacts/` | absent by design (gitignored); `native/xray/` holds only `README.md` |

## What must build (the gate)

The one missing artifact is the gomobile-built `native/xray/artifacts/libxray.aar`
(per-ABI `jni/<abi>/*.so` payloads) plus its `libxray-artifact.json` manifest,
covering the full release ABI set from `ripdpi.nativeAbis`
(`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`).

### Toolchain prerequisites (none present on the current build host)

1. **Go** matching upstream libXray `go.mod` (currently `go1.26.2+`).
2. **gomobile/gobind** pinned to `golang.org/x/mobile@<gomobile pin>`, then `gomobile init`.
3. **NDK 29** (`ripdpi.nativeNdkVersion` in `gradle.properties`; the host currently
   ships NDK 28.2 only) reachable via `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT`.
4. **x86_64 build host.** The NDK ships no `linux-aarch64` host toolchain, so
   `gomobile bind` panics `unsupported GOARCH: arm64` on Apple Silicon. Run the lane
   on an x86_64 CI runner or an amd64 (qemu) container — see
   `scripts/native/libxray-build.Dockerfile`.

### Ordered steps

- [ ] Provision an x86_64 host (CI runner or amd64 container) with Go + gomobile + NDK 29.
- [ ] `scripts/native/build-libxray.sh --check-toolchain` → expect "Toolchain check passed".
- [ ] `scripts/native/build-libxray.sh` (stable channel, full ABI set) → writes
      `native/xray/artifacts/libxray.aar` + `libxray-artifact.json`.
- [ ] `scripts/native/verify-libxray-artifacts.sh` → expect `OK: libXray artifact verified`
      (per-ABI `.so` present, no drift, payload under the 160 MiB budget).
- [ ] `./gradlew :core:engine:verifyLibXrayArtifacts` → same gate, inside the build graph.
- [ ] Confirm the `xray-core` pin in `gradle/libs.versions.toml` still matches the
      `go.mod` xray-core that libXray `v26.3.27` vendors; the build script fails closed
      on a mismatch (exit 71). If it diverges, bump both pins together in one PR.

## The CI seam (what to add)

The verify gate is wired into Gradle but **no `.github/workflows/*` job invokes it
yet** (`grep -rl 'libxray\|verifyLibXray' .github/workflows/` → empty). To close the
"CI fails on missing/oversized/drifted artifact" criterion, add a dedicated workflow
(suggested `native-libxray.yml`) that:

1. Runs on an `ubuntu-latest` (x86_64) runner, or builds the amd64 container from
   `scripts/native/libxray-build.Dockerfile`.
2. Installs Go + gomobile (pinned) + NDK 29.
3. Runs `scripts/native/build-libxray.sh` then `scripts/native/verify-libxray-artifacts.sh --release`.
4. Uploads the `.aar` via `actions/upload-artifact` (it is never committed).
5. Is **schedule-/dispatch-gated**, not on every PR — the gomobile build is heavy and
   the native payload is large. The downstream Android assemble lanes keep working
   without it because `verifyLibXrayArtifacts` is deliberately detached from `assemble`.

This workflow is the only remaining piece of the `package-libXray` task that is pure
RIPDPI work (no device, no live server) once an x86_64 runner with the toolchain
exists; the other open criteria (real `RunXrayFromJSON`, device egress smoke) need a
linked native engine and a live Xray server on top of the artifact.

## What stays blocked even after the AAR builds

Producing and verifying the `.aar` clears `package-libXray` criteria 2/3/5. It does
**not** by itself satisfy the epic ship definition — those still require:

- **NDK 29 native link** of `:core:engine`/`:core:service`/`:app` against the AAR
  (the real `XrayNativeBridgeLibXrayImpl` replacing the `throwUnlinked` stubs).
- **Device/emulator + live Xray server** egress smoke proving traffic exits the Xray
  outbound (`run-xray`, `bridge-tun`, `add-xray-provider-regression-matrix` criterion 5).
- **`:app` UI/ViewModel test lane** capture (currently also blocked by a pre-existing
  offline `gradle-kotlin-dsl-plugins` cache miss) for `add-xray-profile-ux`.

## Code-complete vs gated — per child (re-verified offline 2026-06-11)

| Child | Offline code-complete | Gated on real bridge / device |
| --- | --- | --- |
| Package libXray for Android ABIs | pins, policy, licence, build+verify scripts, Gradle seam | per-ABI `.aar` build, end-to-end verify run, CI workflow |
| Run Xray as managed VPN relay runtime | all 5 criteria vs `FakeXrayNativeBridge` (`RipDpiXrayRuntimeTest` 14, `XrayProtectFdContractTest` 4) | real `RunXrayFromJSON`/`StopXray`/`XrayVersion` on device |
| Bridge TUN traffic through Xray local inbound | criteria 1–4 (`XrayTunnelHandoffTest` 6, `XrayProviderOrchestratorTest` 13, `XrayDnsLoopRegressionTest` 5) | criterion 5: device egress smoke |
| Surface Xray diagnostics and telemetry | criteria 1/3/4/5 (`XrayProviderDiagnosticsTest` 15) | criterion 2: live probe population (`:core:service`) + Home/Diagnostics Compose |
| Add Xray provider regression matrix | criteria 1–4 + 6 (config golden, lifecycle matrix 6, protect-fd 4, DNS-loop 5, lane index) | criterion 5: device/emulator egress smoke |
| Add Xray profile UX and import flow | criteria 1–3 (`XrayImportParserTest` 10 in `:core:data:catalog`, `XrayServiceModeOptionTest` 4 in `:core:data:runtime-state`) | criterion 4: onboarding-to-finish wiring is a **genuine code gap** (no onboarding file references the Xray validator); criterion 5: `:app` test lane (plugin-cache miss) |

> The profile-UX onboarding gap (criterion 4) is the only **non-toolchain** open item
> in the epic — it is missing code, not a blocked build, and can be closed offline
> independently of the AAR.
