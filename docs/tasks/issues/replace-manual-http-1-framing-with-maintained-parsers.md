---
id: RST-1793903447995667
title: Replace manual HTTP/1 framing with maintained parsers
kind: bug
status: done
area: rust-native
priority: high
owner: HTTP transport maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: library-http1-framing
created: 2026-11-05
updated: 2026-11-06
closed_at: "2026-11-06T08:07:16Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: HTTP/1 codecs integrated in 4a346b9e50e53b7311ef1071d97b976ae4ad889c; 531 local tests and combined source gates passed; CI 42181223749 and all six workflows passed.
---

## Goal

Use maintained HTTP/1 codecs for relay, encrypted DNS, diagnostics, and tunnel upgrade traffic. A paused or truncated body must not become a complete successful response.

## Acceptance criteria

- Cover interim responses, duplicate lengths, transfer coding, chunk delimiters, trailers, EOF, body limits, and upgrade status with regression tests.
- Preserve protected connectors, TLS profiles, exact diagnostic requests, partial measurement evidence, and tunnel bytes after the upgrade head.
- Pass affected Rust suites, lint, independent review, and combined source gates before integration. Observe hosted checks after the authorized push.

## Ownership

- Primary writer: DoH, WebTunnel, and diagnostics-fat-header response consumers, workspace Cargo.toml/Cargo.lock, task/spec records, integration and gates in /private/tmp/ripdpi-http1-framing.
- Apps Script writer: native/rust/crates/ripdpi-apps-script-core only, in /private/tmp/ripdpi-http1-apps-script.
- Diagnostics writer: native/rust/crates/ripdpi-diagnostics-http, ripdpi-diagnostics-runner, ripdpi-diagnostics-telegram HTTP response consumers, in /private/tmp/ripdpi-http1-diagnostics.
- Each writer owns an isolated worktree. Only the primary writer changes the shared lockfile and workspace dependencies. Review agents do not edit files.
