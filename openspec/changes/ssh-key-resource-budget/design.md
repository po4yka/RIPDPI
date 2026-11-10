## Context

Pinned russh 0.65.1 calls synchronous ssh-key and PKCS8 KDF APIs. These APIs have no cancellation callback. The local PoC uses ephemeral loopback keys only. The user selected a resource budget while preserving pinned crypto.

## Goals / Non-Goals

- Goal: bound admitted work, avoid executor starvation, and preserve close ownership.
- Non-goal: preempt an active crypto primitive or guarantee a fixed wall-clock KDF duration on all devices.

## Decisions

- Reuse the vendored decoder's armor reader and parse KDF metadata before decryption. Check all accepted containers, including PPK and encrypted DER under plain PKCS8 armor. Bound text before parsing and use checked arithmetic.
- Use one process-wide Tokio semaphore. Acquire it with cancellation and the existing handshake deadline. Move the permit and credential strings into one owned spawn_blocking job. Always join that job; do not drop its handle on timeout.
- Check cancellation/deadline after join and before public-key authentication. Keep existing transport cleanup on every error.
- Error messages identify resource-policy rejection without key or passphrase data. Keep normal auth failures distinct.

## Contracts and ownership

- Root owns ripdpi-ssh, tests, task/spec, and report. Isolated vendor writer owns keys/format, keys/mod.rs, and README.ripdpi.md. Root imports the patch.
- No Kotlin/JNI/protobuf/storage/wire schema, lockfile, dependency, baseline, or serialized shared registry changes.
- Admission limits are normative in the delta spec. PPK performs Argon2 twice; the budget applies to each run. Scrypt includes all three buffers: checked 128*r*(N+p+1) <= 32 MiB. This retains the standard N=16384, r=8, p=1 key.

## Risks / Trade-offs

- Existing expensive keys are rejected. The error is explicit; keys are never rewritten.
- Active decoding is not preemptible. Close joins the admitted bounded work; real duration depends on device load.
- A global slot serializes simultaneous key logins. Password logins remain independent.

## Migration Plan

No persisted migration. A rollback restores former admission and scheduling. Validate real-crypto red/green regression, boundary tables for every container, ordinary SSH suites and peer encrypted-key interoperability, clippy/format, just ci-gates, architecture health, and locked metadata. Run independent source and async reviews before commit. Hosted CI and Android-device evidence remain separate.
