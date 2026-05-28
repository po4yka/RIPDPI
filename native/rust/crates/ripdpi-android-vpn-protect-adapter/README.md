# ripdpi-android-vpn-protect-adapter

**Layer:** L8 — Android / JNI adapters.
**Artifact:** library, linked into `libripdpi.so`.

## Boundary owner (Kotlin)

The `VpnService.protect()` callback path. It backs the
`jniRegisterVpnProtect(vpnService)` / `jniUnregisterVpnProtect()` exports on
`RipDpiProxyNativeBindings`; Kotlin calls them through
`VpnNativeProtectRegistration` at VPN service start / stop.

## Rust crates it calls

`ripdpi-native-protect` — it registers a `JniProtectCallback` (which implements
the `ProtectCallback` trait) into that crate's global protect-callback registry.

## JNI handle / error / panic / lifecycle expectations

- `register_entry` enters the JNI env, obtains the `JavaVM`, and stores a
  `GlobalRef` to the `VpnService`; `unregister_entry` drops it. Registration
  must be **symmetric** with VPN start/stop or the Java object is pinned.
- `JniProtectCallback::protect(fd)` runs on native worker threads — it calls
  `JavaVM::attach_current_thread` and invokes `VpnService.protect(int)`.
- The `ripdpi-android` facade wraps the register/unregister exports in
  `ffi_boundary`; the registration entry also handles `Outcome::Panic`
  explicitly.
- A failed `protect()` must fail the connection — never proceed unprotected
  (see [`.claude/rules/vpnservice-protect-invariant.md`](../../../../.claude/rules/vpnservice-protect-invariant.md)).

## Plane

**Control-plane.** `protect()` is a per-socket (not per-packet) control call —
it is the one control-plane callback that the data plane depends on.

---
See [`JNI_CONTRACT.md`](../../../../docs/architecture/JNI_CONTRACT.md) §10 for
the protect-callback contract and
[`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) for the crate
taxonomy.
