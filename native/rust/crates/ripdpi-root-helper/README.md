# ripdpi-root-helper

**Responsibility:** the standalone privileged helper — a `bin` crate that runs
as uid 0, accepts `CMD_*` requests over the Unix-socket IPC, dispatches them to
handlers, and executes the privileged primitives.
**Layer:** L5 — platform / privileged. **Kind:** `bin` (`src/main.rs`).

On rooted devices it is extracted from APK assets and launched via `su` by
`RootHelperManager.kt`. It is **not** a `.so` and uses no JNI.

## Stable identifiers / contracts

Honors the `ripdpi-root-helper-protocol` wire contract — the `CMD_*` command
set and the `SCM_RIGHTS` fd-passing scheme. See
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Dependency direction

**Upstream:** `ripdpi-root-helper-protocol`, `ripdpi-privileged-ops`,
`ripdpi-ipfrag`. **Downstream:** none — it is the terminal binary.

## Non-root fallback

The helper runs **only** when `root_mode_enabled` is set and root is available.
Its absence is not an error: the client (`ripdpi-runtime-platform`) detects no
registered helper and falls back to local non-privileged paths. The helper must
never be on the critical path for a non-rooted device.

## Extension checklist

1. Add a handler under `handlers/` for the new `CMD_*`, routed by `dispatch/`.
2. Implement the actual primitive in `ripdpi-privileged-ops`.
3. **Security:** the helper is a uid-0 process boundary — validate every
   request field; treat all input as untrusted; route through security review.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md) §7.
