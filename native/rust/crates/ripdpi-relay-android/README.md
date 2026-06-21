# ripdpi-relay-android

**Layer:** L8 — Android / JNI adapters.
**Artifact:** `cdylib` → `libripdpi-relay.so`.

## Boundary owner (Kotlin)

`RipDpiRelayNativeBindings` (in `core/engine/.../core/RipDpiRelay.kt`). It runs
the encrypted relay transports behind `jniCreate` / `jniStart` / `jniStop` /
`jniPollTelemetry` / `jniDestroy`. The relay runtime ships as its **own** `.so`,
separate from `libripdpi.so`.

## Rust crates it calls

`ripdpi-relay-core` (shared relay backend and capability surface) and
`ripdpi-apps-script-core` (the Google Apps Script relay path), plus
`android-support` and `ripdpi-native-protect` for the JNI-backed `VpnService.protect` callback. `ripdpi-relay-core` in turn pulls the relay transport stack through `ripdpi-relay-tls-transports` and related crates, including VLESS/xHTTP, TUIC, Hysteria2, MASQUE, ShadowTLS, Shadowsocks, Trojan, AnyTLS, Tor, and relay chaining.

## JNI handle / error / panic / lifecycle expectations

- `JNI_OnLoad` runs `lifecycle::jni_on_load_entry(vm)` under `catch_unwind`
  (→ `JNI_ERR` on panic).
- A relay session is a `jlong` handle; lifecycle is
  `create → start → stop → destroy`. `jniStart` returns an `Int` status
  (non-zero = failure); `jniPollTelemetry` returns a `jstring`.
- `jniRegisterVpnProtect` / `jniUnregisterVpnProtect` own the relay library's
  protect slot; register before `jniCreate` / `jniStart` so xHTTP carrier
  sockets are protected before connect.
- Exports use `android_support::ffi_boundary` with the standard sentinels.

## Plane

**Control-plane** at the JNI seam. Relay transport (the data plane) runs natively
inside `libripdpi-relay.so`'s linked-in transport crates; relay failures must
surface as relay errors without tearing down the base proxy/VPN session.

---
See [`JNI_CONTRACT.md`](../../../../docs/architecture/JNI_CONTRACT.md) for the
boundary contract and
[`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) for the crate
taxonomy.
