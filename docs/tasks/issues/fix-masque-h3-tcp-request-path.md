---
title: Fix MASQUE H3 TCP request path
type: task
status: doing
area: relay
priority: high
owner: Codex MASQUE H3 lane
parent: epic-protocol-conformance-tests
blocks: []
blocked_by: []
created: 2026-07-10
updated: 2026-07-10
---

## Goal

Make MASQUE TCP protocol selection explicit so the runtime never treats a defective HTTP/3 request path as supported or lets HTTP/2 fallback conceal an HTTP/3 interoperability failure.

## Scope

- Audit the HTTP/3 TCP request construction and response/stream lifecycle against the applicable HTTP/3 CONNECT standards.
- Add a deterministic, repo-owned HTTP/3 fixture that observes the real request path without permitting HTTP/2 fallback.
- Either repair the standards-compliant HTTP/3 TCP path or reject that protocol choice with a typed error before dialing.
- Preserve the existing standards-based CONNECT-UDP path and ensure fallback telemetry cannot be misread as HTTP/3 TCP success.

## Ship definition

- [ ] A focused regression test reproduces the current defective HTTP/3 TCP behavior before the source fix.
- [ ] A standards-specific local HTTP/3 fixture proves method, pseudo-header/authority/path semantics, response handling, and bidirectional TCP stream behavior.
- [ ] TCP protocol selection is explicit and fail-closed; HTTP/2 fallback cannot hide an HTTP/3 TCP defect.
- [ ] Focused MASQUE and relay-core tests, clippy, formatting, architecture checks, and relay interoperability gates pass.

## Ownership

- Serialized writer: `Codex MASQUE H3 lane` owns `ripdpi-masque`, any MASQUE-specific local fixture code, shared relay-core call-site adjustments, this task file, and generated task-board updates.
- Read-only auditors may inspect standards and implementation paths but must not edit shared files.
