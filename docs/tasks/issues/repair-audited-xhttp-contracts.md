---
id: TRN-1794371418861139
title: Repair audited xHTTP stream and pool contracts
kind: bug
status: done
area: transport
priority: high
owner: Transport maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-audited-xhttp-contracts
created: 2026-11-11
updated: 2026-11-11
closed_at: "2026-11-11T05:45:14Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Four separate fixes published on main; 114 xHTTP and 34 origin tests, 185 VLESS unit tests, 16 relay xHTTP cases and one explicit REALITY Xray peer case passed. Affected clippy, workspace check, ci-gates and independent reviews passed. Exact source and limits are recorded in the archived verification.
---

## Goal

Repair the four confirmed TRN-04 audit defects in the existing native xHTTP modes. Keep TLS and REALITY admission unchanged. Correct the related support documentation.

## Acceptance criteria

- stream-up opens and sends payload without upload response headers or server heartbeat.
- Dropping one completed stream closes its HTTP legs and tasks while sibling streams remain usable; write-half shutdown preserves download.
- Configured paths and queries match the pinned Xray URL semantics.
- A pending pool acquisition uses capacity released by any carrier.
- Each defect has a separate Conventional Commit, regression coverage, independent review, and passes the required source gates before integration into main.

## Ownership

- Stream worker owns F1 and F2: `ripdpi-xhttp/src/relay.rs` except its path helper functions, plus new `src/relay/` regression modules. F1 and F2 remain separate commits.
- Path worker owns F3: `ripdpi-xhttp/src/config.rs`, `src/h2_body.rs`, path helpers in `src/relay.rs`, owning path tests, and `ripdpi-cloudflare-origin/src/path.rs` plus HTTP request matching/tests. The origin helper must decode the same serialized request path. Changes in relay.rs are serialized at integration; no other worker edits the path helper range.
- Pool worker owns F4: `src/pool.rs` and `src/pool/` regression tests; it preserves the existing permit type and avoids changing stream-worker files.
- Root owns task/OpenSpec records, support docs, integration, combined validation and push. All workers use separate worktrees.
- Runtime tests use the existing dev-only Tokio `rt-multi-thread` feature, already provided by the rebased main branch. No manifest, package or version change remains.
- Cargo.lock, Kotlin contracts, locales, baselines and golden fixtures have no writer in this change.
