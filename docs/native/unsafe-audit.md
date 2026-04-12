# Unsafe Audit Guide

This guide is for reviewing and reducing `unsafe` in RIPDPI native code without weakening the invariants the runtime depends on.

## Purpose

- Keep `unsafe` localized, documented, and easy to review.
- Make platform and JNI boundaries predictable.
- Prefer small safe wrappers over repeated inline `unsafe` blocks.
- Validate pure-logic or host-side code with tests before broadening native changes.

## Audit Workflow

1. Start from the hottest or riskiest native entry points, not from a raw grep of all `unsafe`.
2. Group `unsafe` by invariant type: fd ownership, pointer lifetime, aliasing, thread handoff, FFI boundary, or syscalls.
3. Check whether the block is truly required or whether a safe wrapper already exists nearby.
4. If the block is required, shrink its scope and document the invariant directly above it.
5. Prefer one reviewer-friendly abstraction per repeated pattern instead of many ad hoc blocks.
6. Record the outcome as one of: `keep`, `wrap`, `refactor`, or `defer`.

## `SAFETY` Comment Standard

Every non-trivial `unsafe` block must have a nearby `// SAFETY:` comment that answers all of the following:

- why the operation is valid
- what invariant must hold
- who owns the data or fd
- how lifetime, aliasing, or thread-safety is preserved
- what would make the block invalid

Minimum standard:

- One comment block per `unsafe` region.
- Comment the invariant, not the obvious syntax.
- Keep the `unsafe` region as small as possible.
- If a block cannot be justified in one or two sentences, it is probably too large.

Example shape:

```rust
// SAFETY: `fd` is owned by this function until it is transferred to the
// platform wrapper. The call does not outlive the buffer and no aliasing
// mutation occurs while the kernel reads the value.
unsafe { /* ... */ }
```

## Priority Hotspots

Audit these first:

- `native/rust/crates/ripdpi-runtime/src/platform/linux.rs`
- JNI bridge code in `core/engine/src/main/kotlin/.../RipDpiProxy.kt`
- JNI/platform wrappers around socket protection, telemetry handles, and lifecycle state
- `native/rust/crates/ripdpi-runtime/src/platform/*`
- `native/rust/crates/ripdpi-root-helper/*`

Why these first:

- They mix FFI, file descriptors, and platform-specific syscalls.
- They sit on the runtime path and can turn a small bug into a crash or leak.
- They often contain repeated patterns that are good candidates for safe extraction.

## Safe-Wrapper Extraction Targets

Prefer extracting one safe API around each repeated unsafe pattern:

- fd creation, duplication, transfer, and closure
- `setsockopt` / `getsockopt` helpers
- `poll`, `epoll`, and socket readiness helpers
- JNI handle registration, lookup, and release
- `SCM_RIGHTS` message send and receive helpers
- raw pointer-to-slice conversions with length checks
- thread attachment and detach logic for JNI callbacks
- platform-specific socket protection and root-helper IPC

Rule of thumb:

- If the same invariant appears in more than one file, it wants a wrapper.
- If the wrapper still needs `unsafe`, keep that `unsafe` inside the wrapper and expose a safe API.

## Verification Expectations

Use the lightest check that proves the invariant:

- Add or update unit tests for pure logic extracted from `unsafe` regions.
- Add integration tests for wrapper behavior when the code crosses process, socket, or JNI boundaries.
- Use Miri for host-side logic where the code can run without Android or platform-specific syscalls.
- Add regression tests for any bug that required the audit.
- For native syscalls or JNI code that cannot run under Miri, document the invariant and verify by targeted tests plus code review.

Minimum bar before landing a change:

- no new unexplained `unsafe`
- tests for any extracted wrapper
- a clear `SAFETY` comment for every remaining block
- no broad refactor unless the audit found a real repeatable pattern

