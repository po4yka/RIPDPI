---
id: PRX-1794127562206266
title: Keep exit session permits with reconnected sockets
kind: bug
status: doing
area: proxy
priority: high
owner: session-accounting
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-08
updated: 2026-11-08
spec_reason: regression-tested-single-module
---

## Goal

Keep each direct TCP session permit with its physical socket during the first-exchange retry. Restore the existing physical-session contract without changing the advisory cap policy or a public wire/configuration contract.

## Acceptance criteria

- A retry from a preferred IP to the original target releases the old IP permit and counts the new physical connection.
- Failed replacement and relay shutdown release the permit, including registered socket clones.
- Existing direct routing, handshake, and relay tests pass; source CI gates pass.
- An independent review finds no unresolved correctness issue before the fix commit.

## Ownership

- The reconnect writer owns `native/rust/crates/ripdpi-proxy-runtime/` for this fix in this worktree. No parallel writer changes these files.
- The integration owner owns these task records and the generated board after the writer stops.
- The independent trace writer uses a separate worktree and owns the relay trace test lane. No dependency, schema, locale, baseline, or golden files are in scope.

## Evidence

LOC-11 P2-01 was reproduced at base `9673571bba07581f2b99cd02014645af96bd8a88`: one logical target switches from a closed preferred IPv4 socket to its IPv6 fallback while the old IP count stays one and the new count stays zero. The audit report is `/Users/npochaev/Downloads/RIPDPI_network_audits/LOC-11.md`.
