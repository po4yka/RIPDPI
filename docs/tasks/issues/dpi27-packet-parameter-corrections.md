---
id: RST-1794677915101106
title: Correct DPI-27 packet parameter contracts
kind: bug
status: doing
area: rust-native
priority: high
owner: Integrator
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi27-packet-parameter-corrections
created: 2026-11-14
updated: 2026-11-14
---

## Goal

Correct the six DPI-27 audit findings without adding new packet methods or outbound timestamp stripping.

## Acceptance criteria

Each finding has a regression and its own implementation commit. Affected tests, independent byte or kernel checks, strict lint, API contracts, locale lint, and ci-gates pass. Integrate with a fast-forward merge to main and push only the resulting main history, as the user requested.


## Ownership and serialized paths

- Adaptive worker: F1 and F5; adaptive resolver, decision ports, services adaptive ports, desync adapter attempt carrier, proxy-runtime adaptive first-outbound/response/session feedback callers. Separate worktree and separate finding commits.
- IPv4 worker: F2; shared raw TCP packet builder and its new test module, existing ID lease integration, runtime IPv4 candidate comments, and only ripdpi_ip_id_mode_body in all ten locale resource sets. This worker is the sole locale writer.
- BPF worker: F3 and F6; linux/bpf.rs and its new kernel test module, test-only post_connect filter comparison, proto timestamp comment, and gated wire timestamp test. This worker is the sole proto writer; no schema numbers change.
- Integrator: F4 converter/protocol.rs and converter tests, task/OpenSpec records, generated API snapshots through the owning tool, combined validation and integration. No worker edits generated reports, lockfiles, baselines or goldens.
- Review agents are read-only. Shared Cargo.lock, version catalog, registry/schema constants and goldens are outside the implementation scope.
