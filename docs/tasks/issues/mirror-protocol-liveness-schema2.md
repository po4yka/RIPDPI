---
id: DAT-1788011816707517
title: Mirror protocol liveness schema 2
kind: chore
status: doing
area: data
priority: high
owner: codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: mirror-protocol-liveness-schema2
created: 2026-08-29
updated: 2026-08-29
---

## Goal

Keep RIPDPI's vendored protocol-liveness policy contract byte-identical to
`po4yka/ripdpi-vpn-deploy@fde031a4ba771dbc96f80a839ee63cf125ddd71f`
without adding client runtime behavior or changing network-exposure contracts.

## Acceptance criteria

- [x] The vendored `protocol-liveness.schema.json` is byte-for-byte equal to
  the deployment producer at the frozen revision.
- [x] JSON Schema draft validation and the complete 22-file contract mirror
  comparison pass.
- [ ] Client data, task/OpenSpec, architecture, configured hook, and exact-head
  hosted CI gates pass without Kotlin, Rust, device, or emulator changes.

## Ownership

This task exclusively owns
`core/data/src/test/resources/contract/protocol-liveness.schema.json` and its
task/OpenSpec evidence. The deployment producer, client runtime, exposure
contracts, shared `main`, devices, and emulators are out of scope.
