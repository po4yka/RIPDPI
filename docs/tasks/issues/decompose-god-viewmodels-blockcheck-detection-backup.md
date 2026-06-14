---
title: "Decompose BlockcheckViewModel, DetectionCheckViewModel, BackupRestoreViewModel"
type: task
status: done
area: ui
priority: medium
owner: unassigned
parent: epic-june-2026-audit-remediation
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-14
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 Kotlin audit confirmed `MainViewModel` was successfully refactored (548 → 247 lines via owner/action extraction) but three new god ViewModels emerged:

- `BlockcheckViewModel` (522 lines) — probe orchestration, ranking accumulation, strategy application, domain scanning, recommendation persistence all in one class.
- `DetectionCheckViewModel` (491 lines) — detection orchestration, result accumulation, UI state, an in-VM probe loop.
- `BackupRestoreViewModel` (449 lines) — export, import, share, preview: four independent workflows.

These are high-churn, hard-to-test classes. The fix pattern is already proven in this codebase (the `MainConnectionActions` / `MainLifecycleStateOwner` / `MainUiStateOwner` extraction used for `MainViewModel`).

## Proposed change

1. `BlockcheckViewModel`: extract a `BlockcheckProbeOrchestrator` use-case (probe loop + ranking accumulation) and move recommendation persistence into a repository; the VM holds UI state and delegates.
2. `DetectionCheckViewModel`: move the in-VM probe loop and result accumulation into a repository/use-case; VM exposes state only.
3. `BackupRestoreViewModel`: split the four workflows — either two VMs (backup vs restore) or extract export/import into a coordinator the VM drives.
4. Follow the existing owner/action decomposition idiom; keep token consumption (RDS) unchanged.

## Acceptance criteria

- [x] Each of the three VMs drops below a reasonable size threshold (target < ~250 lines) with single-responsibility collaborators extracted.
- [x] Extracted use-cases/repositories are unit-tested in isolation (the point of the split).
- [x] No behavior change in the three screens — existing Roborazzi goldens unchanged (no bless).
- [x] `:app:testGithubDebugUnitTest` and `staticAnalysis` green; detekt baseline NOT extended.

## Resolution (2026-06-14)

Done on `worktree-decompose-godvms`, one atomic commit per VM, each gated by a
`kotlin-design-auditor` adversarial-review pass (all PASS, no required fixes).

| ViewModel | Before | After (file / class body) | Collaborators extracted |
| --- | --- | --- | --- |
| `BlockcheckViewModel` | 526 | 231 / ~116 | `BlockcheckProbeOrchestrator` (use-case), `BlockcheckRecommendationRepository` (repo), `BlockcheckReport` |
| `DetectionCheckViewModel` | 510 | 144 / ~88 | `DetectionRunCoordinator`, `DetectionResultPresenter` (pure), `DetectionAuxStateOwner`, `DetectionPermissionStateOwner`, `DetectionCheckStateReducer` |
| `BackupRestoreViewModel` | 447 | 180 / ~158 | `BackupExportCoordinator`, `BackupImportCoordinator`, `BackupResetCoordinator`, `BackupRestoreUiModels` |

Notes:
- Pure refactor — public ViewModel surfaces, effect mechanisms (Blockcheck/Detection
  StateFlow-only; Backup four `MutableSharedFlow` channels with `tryEmit` + buffer-1/
  DROP_OLDEST), and Hilt graphs preserved. Compose layers untouched; screenshot goldens
  unchanged (no bless). No baseline extended.
- New isolated collaborator unit tests: Blockcheck 20, Detection ~30, Backup 38.
- Build task is flavored: `:app:testGithubDebugUnitTest` (the original `:app:testDebugUnitTest`
  does not exist; `--locked` is a cargo flag, not gradle).
- Minor honest caveat (Blockcheck): a transient `Idle`+diagnoses snapshot is now folded into
  the clean `Running` snapshot — a conflated-StateFlow transient no UI observes.
- `introduce-vpn-session-hilt-scope`: not needed — every collaborator is ViewModel-lived,
  none belong to a VPN-session lifetime.

## Risks / open questions

- This is a refactor, not a feature — guard against scope creep; do each VM in its own commit.
- Confirm Hilt wiring for the extracted collaborators stays in the correct component (coordinate with `introduce-vpn-session-hilt-scope` if any belong to a session lifetime).

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item 12).
- Proven precedent: `MainViewModel` owner/action extraction.
- `compose` / `kotlin-design-auditor` skill conventions.
