# android-support

**Layer:** L8 support.

`android-support` contains shared Android/JNI primitives used by the Android-facing native crates: FFI boundary helpers, handle-registry support, logging setup, and panic containment utilities.

## Boundaries

- Used only by Android/JNI adapter crates and artifact roots.
- Must not be pulled into JNI-free runtime, relay, diagnostics, strategy, or protocol crates.
- Keep broad Android concerns here only when they are reusable across more than one native Android adapter.

## Checks

Run focused checks with `cargo test -p android-support`.
