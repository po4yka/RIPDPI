## Context

The report tests the native Vision stream against Xray commit 12ee51e4bb1d02ece4ef4b7114efa2bcdc130995. The general interop runner has a different pin.

## Goals / Non-Goals

- Goal: repair F1, F2 and F3 with separate regression commits and resolve H1-H3 with tests.
- Non-goal: native TLS/TCP, Vision over xHTTP, UI, device probes or new protocol variants.

## Decisions

- Parse bounded TLS handshake and extension lengths; use safe slices and a TLS 1.3 cipher allowlist.
- Detect mismatching UUID prefixes early. A partial matching UUID at EOF remains unpadded fallback; a full matching UUID commits to frame parsing.
- EOF after a complete Continue chunk is valid. EOF within a recognized frame is an error.
- Review unknown-command handling against valid wire behavior before tightening it.
- Keep spec and runner compatibility pins explicit; do not re-pin as a side effect.

## Contracts and ownership

Only ripdpi-vless stream behavior changes. No Kotlin/JNI/protobuf/storage migration. Root owns planning and integration; separate isolated workers own detector, decoder and lifecycle tests. vision.rs merges are serialized. Shared lockfiles, locales and baselines stay unchanged.

## Risks / Trade-offs

Stricter parsing can reduce Direct use for malformed input but retains End fallback. Frame truncation now reaches the caller as an error. Bound handshake buffering; do not add unsafe code or dependencies.

## Migration Plan

No migration. Run red/green regression tests, crate tests and Clippy, pinned loopback interop, independent review, just ci-gates, architecture health and locked metadata before integration. Revert individual finding commits to roll back. Hosted CI evidence remains separate from local evidence.
