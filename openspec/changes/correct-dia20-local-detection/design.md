## Context

DGN-1794755305945452 repairs F1 through F8. The existing Detection Check runs the TLS checker and Android package inventory on the IO dispatcher.

## Goals / Non-Goals

Report local observations with provenance and execution status. Keep network fingerprint capture, browser parity and privileged inspection outside this change.

## Decisions

- Read the enabled JSSE configuration once. On failure or incomplete values, emit a typed outcome without a positive result. Use reference coverage and the configured ID as local descriptors.
- Use active evidence for owner identity. Treat name-only clues as review data outside the direct detection axis and scoring.
- Scan each candidate APK once per run under a bounded budget. Preserve available data when Android or ZIP reads fail. Check cancellation between operations.
- Redact inventory data before formatters render private reports. Export TLS outcomes in each format, including skipped and failed.
- Keep the shared model, narrative, locale and snapshot changes on the integration worktree, with isolated checker, inventory and export lanes.

## Risks / Trade-offs

Package queries reflect Android visibility. Large inventories can report a partial result. Android provider and process behavior require device acceptance to verify.

## Migration Plan

No stored-data migration. Validate consumers of the existing result model and the new local-evidence behavior.
