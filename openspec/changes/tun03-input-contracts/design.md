## Context

The audit identified five defects at Kotlin input and compatibility boundaries. The native AWG profile and supported wire behavior stay unchanged. See proposal.md for the linked task and user outcome.

## Goals / Non-Goals

- Goal: preserve endpoint and supported wire values, reject invalid input before activation, and keep incompatible stored rows intact.
- Non-goal: alter native transport, add peer versions or carriers, change route policy, or claim device or external-server acceptance.

## Decisions

- Store bare IPv6 in the URI model; add syntax brackets only when formatting an authority or Endpoint.
- Accept a set HP key only when decoded length is 32 and canonical Base64 re-encoding equals input. Preserve unset and zero-key semantics.
- Reject malformed query tokens and duplicate names. Keep supported optional empty values unchanged.
- Strict-decode only the stored wire obfuscation object. Keep explicit marker and header-type guards and tolerant top-level metadata. Do not validate blank sanitized credentials as an active request.
- Validate carrier membership before runtime resolution. Only an omitted field keeps the UDP default.
- Keep F1 through F5 as separate reviewed fix commits.

## Contracts and ownership

- URI worker: runtime-state URI code and existing core/data and app import tests; serialize its F1 and F3 slices.
- Key worker: data/model HP key validation and owning model tests.
- Compatibility worker: core/data repository and Room tests for F4; runtime-state request and service resolver/tests for F5.
- Coordinator: all task/OpenSpec artifacts, combined verification, review, and integration.
- No Rust crate, JNI signature, protobuf, dependency, locale, baseline, or golden fixture changes are planned. No new serialized field is added.

## Risks / Trade-offs

- Previously accepted malformed keys, queries, or carrier tokens will fail early. Regression tests retain supported values and absent-field defaults.
- Strict decoding the whole stored request would reject unrelated metadata or sanitized secrets. Limit strict decoding to wire obfuscation and prove exact row/credential preservation with Room.
- Run affected model/runtime-state/data/service unit tests and the app import regression, affected Kotlin lint/format gates, just ci-gates, task validation, architecture health, and locked Cargo metadata before integration. Use the machine build gate and isolated build outputs.
- Require independent read-only review before each behavior fix is committed. Re-run affected checks after any relevant edit or rebase.

## Migration Plan

No database or native wire migration is needed. Existing valid profiles and omitted-carrier defaults retain their meaning. Unsupported rows remain stored but unavailable for activation. A normal source revert can undo validation changes; no data rewrite is required. Device, hosted CI, APK artifact production, and deployment are outside this local parser/validation repair and are not claimed as passed.
