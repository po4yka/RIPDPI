# RIPDPI Roadmap

This file tracks the **forward-looking** roadmap. All previously planned
streams (audit, architecture refactor, bypass techniques and modernization,
integrations, the cleanup epic, and Phases A–E of incremental hardening) are
complete in repo-owned scope.

For completed work see:

- `git log --oneline` — every landed change.
- `docs/architecture/README.md` — ADR index for the architectural decisions
  taken during the cleanup epic and Phase A–E.
- The individual ADRs (`docs/architecture/adr-005`..`adr-013`) — each
  records the decision, the implementation, and any remaining sub-tasks.

## Open Follow-ups

These items have an owning ADR or design doc and are not blocking the current
release.

| Area | Item | Tracked in |
|---|---|---|
| Offline Learner | Shared-priors Kotlin transport (Retrofit + JNI bridge) and embedded production release key (parser, manifest verifier, and fail-secure apply pipeline landed) | ADR-011 |
| Offline Learner | Emulator / sim-to-field calibration: Android-side `EnvironmentDetector` + per-family calibration-factor research spike (type-system segregation landed) | ADR-011 |
| Monitor | WorkManager scheduler hookup + Kotlin `EncryptedSharedPreferences` cache that consumes the new `CdnEchUpdater` persistence API (Rust persistence API landed) | ADR-012 |
| io_uring | Acceptance benchmarks for the park/unpark and registered-buffer TX paths | ADR-013 |

## Roadmap Hygiene

- Update this file in the same change as every roadmap-scoped implementation.
- The "Open Follow-ups" table is the contract; if you close an item, remove
  the row in the same commit. If you discover a new item, add the row.
- Do not accumulate dated "completed in Phase X" sections here. The git
  history and the ADRs are the canonical record of completed work; this
  file is the next-step list.
