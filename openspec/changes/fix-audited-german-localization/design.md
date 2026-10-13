## Context

The German audit at ddd0024d4 found 107 active resource defects, five unused entries, three dynamic localization families and a shared Telegram unit error. See proposal.md.

## Goals / Non-Goals

- Goal: Correct all confirmed entries and diagnostic captions with regression evidence.
- Non-goal: Change disputed Authority/Tail terms, protocols, raw evidence, native schemas, dependencies or unrelated golden fixtures.

## Decisions

- One resource writer owns all XML and the export manifest. New captions land in every locale together.
- Map known Telegram statuses to resources; preserve unknown values and raw diagnostic details. Preserve existing status tone logic.
- Localize human-readable share prose without translating technical IDs or user/error text. Preserve summary sections and privacy boundaries.
- Resolve current app locale through configuration or the existing language-aware context. Locale changes invalidate display formatting.
- Convert native byte rates to bits using floating-point arithmetic before formatting; never multiply a Long by eight. Preserve thresholds and correct unit labels at all boundaries.
- Reuse existing test/render support. Compare existing golden fixtures without recording. Any intentional fixture change requires separate explicit approval.

## Contracts and ownership

- App presentation and tests change; service and Rust sources do not. No wire, storage, configuration or dependency migration.
- Ownership is recorded in the portfolio task: resource, runtime, validation and integration worktrees are separate. Locale XML and manifest are serialized.

## Risks / Trade-offs

- Long German text: inspect representative controls and warnings at normal and enlarged font scales.
- Unknown native status: preserve original text as fallback and retain raw evidence.
- Throughput overflow or misleading units: test zero, threshold values and Long.MAX_VALUE; cover Telegram cards and share output.
- Simple screenshots require a real private relay bundle. The previous scoped search found no usable input. Report this blocker; do not insert placeholder profiles.

## Migration Plan

No data migration. Revert the scoped commits to roll back. Validate German resource regressions, all-locale parity/export, targeted and full app tests, staticAnalysis, app/service lint, architecture health, file limits, locked Cargo metadata, task contracts and existing Full/Simple screenshots where inputs exist. Rebase and run combined-tree gates before authorized main integration and push.
