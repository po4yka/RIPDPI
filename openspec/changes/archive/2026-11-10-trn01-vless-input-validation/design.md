## Context

The audit reproduced malformed addons acceptance in four decoder paths and unsupported Flow acceptance in the plain TCP origin. The pinned reference is Xray-core 12ee51e4bb1d02ece4ef4b7114efa2bcdc130995. Outbound bytes remain unchanged.

## Goals / Non-Goals

- Goal: validate the bounded protobuf block once per header and expose exact Flow to the consumer.
- Goal: reject unsupported origin flows before any target effect.
- Non-goal: add Vision to xHTTP, add IDNA or transport modes, change pins, use credentials or probe devices and external servers.

## Decisions

- Use one private safe addons decoder with no added dependency. Match the pinned protobuf behavior for known fields, unknown fields, groups, lengths and varints; preserve last Flow value.
- Add flow: String to the internal decoded request header. Configuration aliases are not wire aliases.
- Keep at most 255 addon bytes and partial-read progress in the lazy response stream; validate before yielding payload and retain a terminal failure state.
- Keep the eager helper, state its non-resumable cancellation contract, and direct resumable callers to the existing lazy wrapper.
- Keep each correction in a separate Conventional Commit. Do not change gates or baselines to fit the implementation.

## Contracts and ownership

- Rust crates: ripdpi-vless and ripdpi-cloudflare-origin; no Kotlin, JNI, persisted, version or on-wire schema change.
- Decoder worker owns addons.rs, addons/**, wire.rs and wire/** in its isolated worktree. Only this worker writes wire.rs until F1 is committed.
- Origin worker owns session.rs and session/** in a separate worktree; depends on the decoded flow field from F1.
- Integrator owns task and specification artifacts, combined checks and main integration. The decoder worker includes helper cancellation docs in F1.
- Cargo.lock, manifests, golden fixtures, locales and baseline files remain untouched.

## Risks / Trade-offs

- Handwritten protobuf behavior can diverge: check independent upstream vectors, unknown groups, duplicate fields, UTF-8 and overflow; review the parser independently.
- Stricter validation rejects malformed fixtures: replace only invalid positive inputs with valid protobuf bytes and retain negative assertions.
- An additional request-header field can affect callers: build direct consumers and inspect struct literals and wire parsers.
- No per-byte logs or peer values in errors. No unsafe or shared mutable parser state is needed.

## Migration Plan

No stored-data migration. Apply decoder, origin guard and documentation commits in that order. Rollback uses a normal reviewed revert, not history rewriting.

Local gates: cargo test --locked -p ripdpi-vless --lib; cargo test --locked -p ripdpi-cloudflare-origin; affected xHTTP and local fixture tests; targeted Clippy; independent general and async review; cargo metadata --locked; taskctl validate; architecture check and just ci-gates. Use repository Rust 1.98.1, build-gate lane ripdpi and at most four jobs. Report hosted CI and device results separately; no release or deployment is part of this change.
