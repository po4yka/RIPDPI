---
id: RST-1793945484896628
title: Replace manual Rust Protobuf codecs
kind: bug
status: blocked
area: rust-native
priority: high
owner: Rust implementation
parent: null
blocked_by: []
spec_mode: required
openspec_change: replace-rust-protobuf-codecs
created: 2026-11-06
updated: 2026-11-06
status_detail: Hosted rust-lint fails on pre-existing Rc<RefCell> in ripdpi-diagnostics-http response_decoder.rs (lines 48 and 185); Protobuf tests and local gates passed.
---

## Goal

Decode standard GeoSite and gRPC Hunk messages with a maintained Protobuf codec. Reject malformed input and unsupported gRPC compression without losing valid stream data.

## Acceptance criteria

- Standard unknown fields, field order, repeated scalar fields, and empty messages follow Protobuf rules.
- GeoSite uses the existing geosite.proto schema; sing-box geosite.db remains supported.
- Invalid Protobuf, invalid UTF-8, oversized Hunk messages, unsupported compression, and truncated streams fail explicitly.
- Regression tests fail before the fix and pass after it; crate tests, lint, dependency checks, and CI source gates pass.
- Independent review passes; commits reach main and origin/main with hosted CI evidence.

## Ownership

One implementation writer owns both crates, workspace Cargo.toml/Cargo.lock, and this change. Review agents are read-only. No parallel writers use the serialized dependency or schema lanes.
