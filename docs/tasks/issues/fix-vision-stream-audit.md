---
id: TRN-1794371406782186
title: Fix Vision stream audit findings
kind: bug
status: doing
area: transport
priority: high
owner: Transport maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-vision-stream-audit
created: 2026-11-11
updated: 2026-11-11
---

## Goal

Fix the TRN-02 Vision detector and decoder defects. Check the related half-close hypotheses without adding transports.

## Acceptance criteria

- TLS 1.2 cannot arm Direct through a marker outside supported_versions.
- TLS 1.3 detection handles record fragmentation and excludes CCM_8.
- Incomplete recognized frames return an error; short unpadded replies keep all bytes.
- Unknown commands and asymmetric half-close have an explicit tested disposition.
- Crate tests, Clippy, independent review, pinned loopback interop and CI source gates pass.

## Ownership

- Root integrator: task/OpenSpec records, SPEC_VERSION.md clarification, combined validation and integration.
- tls_detector: isolated worktree; vision.rs TLS detector fields/helpers and detector tests, F1/H1.
- vision_decoder: isolated worktree; vision.rs unpad/read fields/helpers and decoder tests, separate F2/F3 commits; H2 policy.
- half_close: isolated worktree; transport/half-close tests and a proven lifecycle fix only, H3.
- Shared vision.rs is merged serially by the root integrator. No dependency, locale, baseline or generated artifact changes are planned. Review agents are read-only.
