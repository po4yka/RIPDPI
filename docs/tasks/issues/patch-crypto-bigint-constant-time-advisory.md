---
id: RST-1792822623929592
title: Patch crypto-bigint constant-time advisory
kind: chore
status: doing
area: rust-native
priority: high
owner: primary
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-24
updated: 2026-10-24
spec_reason: dependency-only
---

## Goal

Remove the published crypto-bigint constant-time advisory from the native dependency graph. This unblocks required CI for the stored-profile upgrade and Hysteria port hopping delivery without weakening security policy.

## Acceptance criteria

- Resolve crypto-bigint 0.7.6 and its required ctutils 0.4.5 patch with official registry checksums. Change no other package records, dependency requirements, toolchain, vendor code, or advisory exceptions.
- Preserve the unrelated crypto-bigint 0.5.5 resolution. The fuzz dependency graph contains no crypto-bigint package and is not changed.
- Verify locked metadata, the complete advisory/bans/licenses/sources check, and affected SSH/crypto consumers. Obtain terminal required CI for the exact submitted head before integration.
- Keep the task open while required checks are pending; this update does not close the profile-quality or hopping tasks.

## Ownership

Primary owns native/rust/Cargo.lock and this task's records. Native hopping source and unrelated worktrees do not edit the lockfile. This dependency-only patch is a prerequisite of the profile upgrade and native hopping capabilities.

## Evidence

- PR #659 run 40068894755 reports RUSTSEC-2026-0387 for crypto-bigint 0.7.5. Base 261f3019 and source head fb6a4e616 have identical dependency inputs; the failure predates this source fix.
- The official advisory specifies crypto-bigint 0.7.6 as patched. Its manifest requires ctutils 0.4.5. Pinned Rust 1.98.1 satisfies both package MSRVs.
- Exact package update, locked metadata and reverse-dependency inspection passed. Full locked cargo-deny advisory, bans, licenses and sources checks passed. Independent source review found no actionable defect. Affected native consumer tests and terminal hosted checks remain required.
