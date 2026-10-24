---
id: RST-1792838334204120
title: Admit Hysteria UDP sockets into the async runtime
kind: bug
status: doing
area: rust-native
priority: high
owner: protocol-runtime
parent: null
blocked_by: []
spec_mode: required
openspec_change: hysteria-nonblocking-socket
created: 2026-10-24
updated: 2026-10-24
---

## Goal

Start Hysteria Salamander traffic and rebind its endpoint without a blocking-socket runtime panic.

## Acceptance criteria

- Bound blocking and nonblocking sockets exchange real asynchronous datagrams through Salamander.
- Initial endpoint creation and rebind use the same admission rule and preserve socket protection.
- Complete crate tests, Clippy, formatting, and independent review pass on the integrated source.
- Physical Hysteria data transfer passes on the rebuilt Android artifact. Local tests alone do not close this task.

## Ownership

- Protocol runtime worker owns the Salamander constructor, crate regression tests, and this task/spec.
- Integration owner owns shared integration history and physical Android acceptance. No concurrent edits to these files.
