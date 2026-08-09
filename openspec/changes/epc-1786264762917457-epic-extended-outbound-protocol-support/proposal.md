# Change: Epic - Extended outbound protocol support

Task ID: `EPC-1786264762917457`

## Why

> 2026-06-01 — scope reduced per ADR 0004. VMess, Trojan-Go, and Hysteria v1 are dropped from this epic and removed from the codebase — they were never-completed stubs that carried no traffic, and RIPDPI maintains support only for current/actual protocols. The remaining open backlog is SSH and Mieru only (not-yet-implemented compatibility work, explicitly not legacy). Their child tasks are deleted

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `epic-extended-outbound-protocol-support`: Epic - Extended outbound protocol support

### Modified Capabilities

- None.

## Impact

- Portfolio area: `epic`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
