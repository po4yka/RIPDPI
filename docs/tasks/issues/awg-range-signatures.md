---
id: TRN-1792776081374051
title: Support AWG 3.1 header ranges and signature packets
kind: feature
status: review
area: transport
priority: high
owner: AWG client implementation
parent: null
blocked_by: []
spec_mode: required
openspec_change: awg-range-signatures
created: 2026-10-23
updated: 2026-10-24
status_detail: Reviewed source and exact 47a5ee708a497086eb29739ca17f35e164119846 genuine artifacts passed all four owned physical USB scenarios and cleanup. Final published hosted gates remain required before closure.
---

## Goal

Import and run the AWG 3.1 range-and-signature wire profile with the existing native engine. Keep S3 and S4 zero.

Risk is high because this changes authenticated wire behavior and cross-module contracts.

## Acceptance criteria

- Canonical string header ranges and five signature packet tags survive import, storage, activation and sharing.
- Real native traffic interoperates with the pinned upstream AWG 3.1 peer, including DNS and reconnects.
- Invalid ranges, templates and unsupported packet modes fail before activation. Existing cookie authentication remains active.
- Native and Android compile/test gates pass. Physical ARM64 verification is recorded separately and remains open until observed.

## Ownership

- Client owner: AWG Rust codec, Kotlin models, import/export, activation, editor, mirrored contracts and related tests.
- Integration owner: deployment schema, emitter, canonical fingerprint and goldens, PR integration and independent review.
- The Xray security lane owns its verifier and harness. No shared files are edited concurrently.

- Integration owner also owns separation of client-only import vectors from byte-identical producer contract mirrors, including their existing import tests.
