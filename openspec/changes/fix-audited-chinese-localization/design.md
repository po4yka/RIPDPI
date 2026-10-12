## Context

The audit baseline is main at 76169878cae9b947e58140d2a2d788dbaf7466bc. Resource keys are complete, but 116 active Chinese entries have missing or inaccurate translations. Known Kotlin tool results and saved dates also bypass localization.

## Goals / Non-Goals

- Goal: Correct all confirmed active findings, dormant redaction wording, and Quick Connect label consistency.
- Non-goal: Change speculative terminology suggestions, network behavior, layouts, dependencies, machine reports, or golden fixtures.

## Decisions

- Reuse existing resources where the meaning matches. Add resource mappings for known tool verdicts and errors; keep unknown and custom values unchanged.
- Translate RKN aggregate results at the app presentation boundary. Do not change core result strings or serialized contracts.
- Resolve date locale at presentation time from the selected app locale. Keep persisted timestamps unchanged.
- One writer owns every locale XML set and the translation manifest. Code writers supply exact key and argument requests.

## Contracts and ownership

- App owns all changed production presentation code. Core result contracts remain unchanged.
- No Rust, JNI, protobuf, storage, or service-lifecycle change. No migration.
- Separate writer worktrees and serialized ownership are recorded in the portfolio task.

## Risks / Trade-offs

- Known errors can coexist with custom text: test unknown fallback preservation.
- Chinese text may wrap differently: verify the five existing Chinese screenshot scenes and inspect actual images. Do not record or bless fixtures.
- Other locales need translations for new dynamic resources: check every locale and preserve all argument contracts.

## Migration Plan

Run targeted regression tests, all app and service unit tests, staticAnalysis, app/service lint, locale parity, translation export, architecture health, locked Cargo metadata, and Chinese screenshot verification before integration. Use build-gate and one Gradle worker for stable native rendering. Revert the scoped commits to roll back. Hosted CI and physical-device evidence remain separate.
