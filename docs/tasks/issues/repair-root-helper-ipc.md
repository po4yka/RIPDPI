---
id: VPN-1794196711383747
title: Repair root-helper IPC audit findings
kind: bug
status: review
area: vpn
priority: high
owner: IPC repair integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-root-helper-ipc
created: 2026-11-09
updated: 2026-11-09
status_detail: Repairs and local validation recorded; BPF, hosted CI and device acceptance remain open.
---

## Goal

Repair the current VPN-09 IPC defects and preserve the existing root opt-in and non-root contracts.

## Acceptance criteria

Each confirmed finding has its own reviewed commit and passing regression. Run affected native and Kotlin checks, source gates, metadata and Android compile. Record remote and device gaps without overstating acceptance.

## Ownership and serialized paths

- fd_cloexec_fix: privileged-ops linux/fd.rs, tcp_repair.rs and fd tests; isolated vpn09-cloexec-20261109.
- ordered_limits_fix: privileged-ops ordered payload reconstruction and raw-packet tests; helper ordered regression tests only by coordination; isolated vpn09-payload-limits-20261109.
- protocol writer: helper main/dispatch socket validation, protocol receive deadline, runtime client and owning tests; separate worktree.
- Integration writer: task/spec artifacts, root-helper contract docs, combined gates and delivery.
- No parallel writer changes lockfiles, schemas, registries, locales, golden fixtures or baselines.
