# ripdpi-android-telemetry-adapter

**Layer:** L8 — Android / JNI adapters.
**Artifact:** library, linked into `libripdpi.so`.

## Boundary owner (Kotlin)

No `Java_*` exports of its own — this crate **does not depend on `jni`**. It is
the telemetry **projection** layer: it is installed process-wide as the runtime
telemetry recorder by `ripdpi-android`'s `JNI_OnLoad` (`install_recorder()`),
and the snapshots it produces are read by the Kotlin telemetry coordinators
(`ProxyTelemetryCoordinator`, `VpnTelemetryCoordinator`, …) through the
poll-telemetry exports of the proxy/tunnel adapters.

## Rust crates it calls

`ripdpi-telemetry` (telemetry data structures), `ripdpi-runtime-api` (the
telemetry sink), `ripdpi-proxy-config`, `ripdpi-failure-classifier`,
`android-support`.

## JNI handle / error / panic / lifecycle expectations

- No JNI exports, no handles. It projects native runtime / adaptive / autolearn
  / direct-path / routing state into deterministic telemetry snapshots and a
  bounded event ring.
- Telemetry payloads are golden-locked contracts; serialization must stay
  deterministic. No raw device identifiers or packet payloads may be recorded.
- The recorder is installed once at library load and is not per-session.

## Plane

**Control-plane.** Pure observation/projection — pull-model, ~1 Hz; never on a
data path.

---
See [`JNI_CONTRACT.md`](../../../../docs/architecture/JNI_CONTRACT.md) for the
boundary contract and
[`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) for the crate
taxonomy.
