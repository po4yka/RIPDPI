---
id: PRX-1794118814905211
title: Repair HTTP CONNECT audit defects
kind: bug
status: review
area: proxy
priority: high
owner: HTTP CONNECT maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: loc05-http-connect
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Repair all seven confirmed LOC-05 findings while preserving real TCP/TLS tunnels.

## Acceptance criteria

- Each finding has a separate implementation commit with a regression test.
- Invalid fields, framing, signed ports and excessive headers fail before upstream use.
- HTTP/1.0 without Host and valid Basic with obs-text work.
- Success follows an established upstream, including delayed routing.
- Affected Rust tests, all-target Clippy, locked metadata, architecture health and ci-gates pass.
- Independent review passes before each behavior commit. Main receives the verified commits and origin/main matches after the authorized push.

## Ownership

- Parser worker: ripdpi-session/src/http_connect.rs and its tests, ripdpi-proxy-runtime-adapter/src/model/protocol_auth.rs. Owns F02, F03, F04, F05 and F06, in separate commits.
- Connect worker: ripdpi-proxy-runtime/src/runtime/handshake/connect_relay/** runtime WS state/glue, obsolete protocol_io success-reply helper/reexport and its owning socket test, docs/native/proxy-engine.md, and dedicated HTTP connect-order integration tests. Owns F01; must preserve payload routing and WS behavior.
- Primary: handshake/protocol_io/readers.rs, handshake.rs, replies.rs and dedicated header-limit tests for F07; all task/OpenSpec records and combined gates/integration.
- Reviewer: read-only review of each prepared fix, no file changes.
- Serialized lanes: primary owns integration, task records and generated board. No Cargo.lock, dependency, schema, locale, baseline or golden changes are planned. Reuse the current PreparedWsTunnel API. Any necessary API snapshot delta requires generation through its owner and primary review. No baseline extensions.

## Audit boundary

The original external audit is historical evidence. H01 DNS cancellation and H02 IPv6 policy are hypotheses, not confirmed fixes; inspect current ownership and report evidence before changing either contract. No physical device, external server or real-secret probes are authorized by this task.
