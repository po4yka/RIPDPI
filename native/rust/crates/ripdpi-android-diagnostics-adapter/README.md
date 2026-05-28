# ripdpi-android-diagnostics-adapter

**Layer:** L8 — Android / JNI adapters.
**Artifact:** library, linked into `libripdpi.so`.

## Boundary owner (Kotlin)

`NetworkDiagnosticsNativeBindings` (in `core/engine/.../core/NetworkDiagnostics.kt`),
driven from the `:core:diagnostics` UI. This adapter implements the scan
lifecycle behind `jniCreate` / `jniStartScan` / `jniCancelScan` /
`jniPollProgress` / `jniTakeReport` / `jniPollPassiveEvents` / `jniDestroy`.

## Rust crates it calls

`ripdpi-monitor-engine` (the active-scan engine), `ripdpi-monitor-proxy-runtime`
(passive proxy-runtime telemetry), `ripdpi-diagnostics-contracts` (the
`ScanRequest` / `ScanReport` wire types), `ripdpi-android-bridge-support`,
`android-support`.

## JNI handle / error / panic / lifecycle expectations

- A scan session is an opaque `jlong` handle; lifecycle is
  `create → startScan → (poll progress / take report) → cancel/destroy`.
- Progress and report payloads are JSON strings (`jstring`, null on panic);
  they are versioned wire contracts (`DIAGNOSTICS_ENGINE_SCHEMA_VERSION`) and
  golden-locked — a payload-shape change is a contract change.
- The `ripdpi-android` facade wraps these entry functions in
  `android_support::ffi_boundary`; adapter-side errors map via `JniProxyError`.

## Plane

**Control-plane.** Diagnostics scans are control-plane orchestration; probe
traffic is generated natively by the monitor/diagnostics crates.

---
See [`JNI_CONTRACT.md`](../../../../docs/architecture/JNI_CONTRACT.md) for the
boundary contract and
[`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) for the crate
taxonomy.
