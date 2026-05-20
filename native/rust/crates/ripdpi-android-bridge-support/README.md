# ripdpi-android-bridge-support

**Layer:** L8 — Android / JNI adapters.
**Artifact:** library, linked into `libripdpi.so`.

## Boundary owner (Kotlin)

No Kotlin class owns this crate directly — it is **shared JNI infrastructure**
used by the `ripdpi-android-*` adapter crates. It defines the error-mapping
contract that the JNI boundary exposes to Kotlin: `JniProxyError` → Java
exception classes (`IllegalArgumentException`, `IllegalStateException`,
`IOException`, `RuntimeException`).

## Rust crates it calls

`android-support` only. It deliberately depends on no runtime or domain crate —
it is leaf JNI plumbing.

## JNI handle / error / panic / lifecycle expectations

- **Error mapping:** `JniProxyError::throw(env)` logs then throws the mapped
  Java exception; `sanitize_error_message` strips internal detail in release
  builds. This mapping is a golden-locked contract (`error_exception_mapping.json`).
- **Handles:** `to_handle(jlong) -> Option<u64>` validates a handle and rejects
  `0` (the "no handle" sentinel).
- **Panic:** `extract_panic_message` / `throw_panic` turn a contained panic
  payload into a `RuntimeException` for callers that surface panics explicitly.
- Provides a `test-support` JVM-test harness behind a feature flag.

## Plane

**Control-plane.** Pure JNI plumbing; never on a data path.

---
See [`JNI_CONTRACT.md`](../../../../docs/architecture/JNI_CONTRACT.md) for the
boundary contract and
[`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) for the crate
taxonomy.
