---
id: TRN-1792853642811403
title: Preserve XUDP source authority for reserve DNS replies
kind: bug
status: review
area: transport
priority: high
owner: UDP relay runtime
parent: null
blocked_by: []
spec_mode: required
openspec_change: xudp-source-authority
created: 2026-10-24
updated: 2026-10-24
status_detail: Implementation, independent review and physical automatic-reserve DNS plus recovery passed; final hosted acceptance remains open.
---

## Goal

Accept valid XUDP replies that omit the original source address, including DNS after automatic TCP reserve selection.

Risk is high because source authority controls where UDP replies enter the client tunnel.

## Acceptance criteria

- Establish omitted-source authority only from the accepted initial NEW target of the same carrier and session.
- Preserve explicit response sources, reject missing authority and malformed session metadata, and prevent cross-destination or cross-generation reuse.
- Verify immediate replies, cancellation, multiple queued targets and fresh-carrier isolation with observable tests.
- Pass affected native and hosted gates, rebuild genuine Android artifacts, and complete the authorized physical Hysteria automatic-reserve DNS and recovery scenario.

## Ownership

- Protocol worker owns the XUDP codec/association implementation in native/rust and adjacent behavior tests in its isolated worktree. Exact paths are recorded in design.md before implementation.
- Coordinator owns task and specification files, integration and validation records. Security worker independently reviews source authority and lifecycle boundaries.
- Android acceptance worker alone owns physical-device operations. No dependencies, public schema, fixture DNS rules, test timeouts or server changes are included.
