---
id: ENG-1794557091175687
title: Close the remaining Disoob audit gaps
kind: bug
status: review
area: engine
priority: high
owner: dpi09-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi09-ttl-safety
created: 2026-11-13
updated: 2026-11-13
---

## Goal

Close the remaining DPI-09 audit gaps on the current main branch. Preserve the
OOB stream-integrity repairs that are already merged.

## Acceptance criteria

- TTL restoration errors stop the send and preserve committed payload progress.
- TTL writes affect only the connected socket address family.
- A send-stage deadline fails when unsent bytes remain, for either wait mode.
- The Disoob packet oracle checks original bytes, urgent sequence position,
  TTL 1 and restored tail TTL, with a strict local echo.
- Platform documentation states the Linux and Android execution boundary.
- Existing OOB progress, single-urgent, offset and generic projection tests pass.
- Independent review, affected locked Cargo tests, Clippy and source gates pass.
- Each remaining issue has a separate commit; integration and push to main
  follow the user's explicit request. No device, external server or root run.

## Parallel ownership

- runtime_recheck: ripdpi-desync-runtime TTL setter and lowering implementation
  and their owning tests; separate commits for H1 and H2.
- smoke_recheck: ripdpi-cli packet-smoke Disoob oracle and its tests, plus
  docs/native/proxy-engine.md; separate commits for F4 and F5.
- contracts_recheck: ripdpi-privileged-ops send-stage deadline (H4) and tests.
- dpi09-integration: portfolio,
  OpenSpec artifacts, combined validation, review fixes, main integration/push.
- contracts_recheck: verification of F2/F3/H3 already merged, then isolated H4 repair.
- Shared Cargo.lock, manifests, locale sets, schemas, baselines and goldens have
  no writer. Task records and specification files have one integration writer.

## Already repaired on main

- F1: 8c48353d1e and 326e396ea6 preserve OOB prefix progress and logical counts.
- F2: b3a51e8971 emits one urgent byte only in the initial original buffer.
- F3: 197df0b548 and c9d964d440 reject unsupported OOB offset repeats and skip.
- H3: 398e05e47c preserves WriteUrgent in the generic strategy projection.
