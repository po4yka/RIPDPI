## Context

Task ID: TRN-1794377577809835. The relay builder currently doubles an unchecked working limit. Yamux 0.14.1 asserts that all initial windows fit its 1 GiB receive budget.

## Goals / Non-Goals

- Goal: reject invalid profile and direct API input without panic or carrier I/O.
- Non-goal: increase memory limits, change wire framing, or change P1 response handling.

## Decisions

- Own receive-budget constants, checked closing reserve, and constructor admission in ripdpi-vless.
- Keep max_concurrent_streams as the logical working limit. Remove the relay builder mutation; derive effective capacity immediately before connection in the library.
- Keep the existing 1 GiB bound and use yamux::DEFAULT_CREDIT to derive the maximum capacity. Do not catch a panic or disable the memory limit.
- Validate the low-level session constructor separately because internal callers can bypass profile parsing.

## Contracts and ownership

- One writer owns ripdpi-vless mux.rs, lib.rs and yamux_session.rs, plus relay-core's VLESS builder and tests.
- No Kotlin, JNI, wire schema, shared dependency, golden, or baseline files change.
- Admission returns a typed MuxConfigError; connection APIs map it to InvalidInput.

## Risks / Trade-offs

- Existing callers that manually doubled the public working limit must use its documented logical meaning. Repository callers are updated together.
- Integer overflow on 32-bit targets is avoided with checked multiplication and a bound below usize::MAX.

## Migration Plan

No stored schema migration or deployment is needed. Unsupported profiles now fail at admission. Revert this change to roll back. Gates: ripdpi-vless unit tests, relay-core mux tests, affected-crate Clippy, rustfmt, cargo metadata --locked, just ci-gates and task validation. Host tests cover deterministic arithmetic and local loopback admission; device, hosted CI and packaged artifacts are not required for this local input-validation repair.
