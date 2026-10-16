## Context

See proposal.md and specs/ui/stage-three-clarity/spec.md. Current settings has
seven sections and repeated Manage values. Simple can apply diagnostic changes
before a later cancel/failure. The neutral tone still has a warning icon.
Navigation already measures labels and chooses four, two or one columns, but
its two-column path repeats a full-height icon row. The exact translation-export
CI failure is missing stage-two keys in the committed manifest.

## Goals / Non-Goals

- Goal: clear settings groups and action consequences, real existing backup
  access, neutral status and less navigation height without lost labels.
- Non-goal: exact diagnostic Undo, new per-network rollback/storage contracts,
  network algorithms, service behavior, profile seeding or performance tuning.
- Non-goal: claim user comprehension, screen-reader speech, real relay access
  or release performance from a component test or a screenshot.

## Decisions

- Group settings Connection, Interface, Privacy, Support; move language to
  Interface, start-on-boot to Connection and data transparency to Privacy.
  Keep permission repairs and contextual security warnings accessible. Show
  existing native locale/theme/persona/icon values. Rows with no trustworthy
  summary keep a chevron and concise intent, without an invented catalog count.
- Keep Simple analysis execution unchanged. Name automatic configuration in
  the action and a short pre-launch explanation. Render actual appliedSettings
  from the existing sheet; cancellation or unavailable outcomes retain an
  explicit notice that changes may have been saved. Preserve disabled reasons.
- Reuse BackupRestoreRoute as a saved-state Simple overlay. Add an optional
  factory-reset visibility parameter with the Full default unchanged. Keep SAF,
  privacy disclosure, import preview and confirmation. Explain FULL settings
  restore versus SHARE and the existing memory/seeder limits in this surface.
  Exact Undo would need receipts in multiple diagnostic writers and conditional
  Room/settings restoration; a ViewModel before/after snapshot is unsafe.
- In fallback navigation preserve measured full labels and all four actions.
  Remove decorative icons in the two-column case; use natural text height and
  at least 48dp targets. Keep normal four-column treatment and stable IDs.
  Allow wrapping in extreme narrow cases. No new chooser or extra tap is needed.
- Choose Info for Nominal degradation treatment and Warning for Warning/Critical.
  The existing tone, safe semantics, neutral colors and reprobe remain.
- Generate translation metadata with its owning script. Keep the exact checker
  in the combined gate so later locale additions cannot repeat the omission.

## Contracts and ownership

Only app presentation, resources, tests, documentation and the generated key
manifest change. No Rust crate, JNI, protobuf, diagnostics apply, persistence,
service, dependency or backup payload contract changes. Full backup UI keeps
its default reset action. Each writer has a dedicated worktree and the portfolio
records exclusive ownership before writing. The integration owner serializes
all locales, shared IDs, DESIGN.md, design-system docs, manifest and task files.
Golden updates need explicit current user approval for the exact affected family
and the isolated golden specialist. No fixture approval is inferred here.

RDS references are components-settings-row.html, components-bottom-nav.html and
vpn-degradation-strip.html. HTML stays read-only. The deliberate bottom-bar
variation trades decorative icons for complete labels in less height.

## Risks / Trade-offs

- Offscreen LazyColumn controls are not composed: scroll by stable key/tag
  before assertions; check complete bounds above navigation at font2.
- Removing Manage must not remove chevrons or actions; SettingsRow defaults
  depend on a value, so explicit action affordance is required.
- A failed/cancelled Simple run may already have applied changes. Do not use an
  absent sheet as evidence of no changes, or backup as exact diagnostic Undo.
- Backup restore restarts Simple. Its existing seeder reselects bundled relay,
  VPN and CLI-off; DNS/WARP/strategy payload is retained. Explain this limit.
- A CI relay fixture permits structural UI checks only; it is never real-network
  evidence. Keep fixture provenance explicit and outside committed app assets.
- Intentional screenshots may change. Inspect actual/diff, request a concrete
  named family approval, then record narrowly and verify without weakening gates.

## Migration Plan

No data migration or dependency is needed. Each atomic slice includes meaningful
regression tests and implementation. Revert ordinary commits to roll back.
First reproduce the manifest failure and verify its owning regeneration. Run
regression tests before fixes where practical, then combined Full and Simple
presentation tests, theme tests, staticAnalysis, app/service lint, real Full
app/test APK assembly, supported Simple structural assembly/render, architecture
health, locked Cargo metadata, task and strict OpenSpec checks. Every compiler
run uses build-gate, four workers and nativeCpuBudget=4 with CARGO_BUILD_JOBS unset.
Use a command-scoped emulator pool lease for light/dark, 360dp/font2 interactions.
Record fresh XML/PNG provenance, restoration and unverified TalkBack/user-study
boundaries. Fetch/rebase the job, check combined source, fast-forward main and
push under the user's explicit authorization; inspect new hosted CI afterward.
