## Context

The current app has reusable run, recommendation, and evidence models. The UI must use these measured facts. It must remain offline first and use the existing RDS theme.

## Goals / Non-Goals

- Goal: Complete the audited run, result, and next-action flow with clear controls and progressive disclosure.
- Non-goal: Add a backend, a network score, a new native probe, or a wire/storage migration.

## Decisions

- Keep one diagnostics screen tree. Use the saved guided/advanced persona to control technical disclosure.
- Put controls before catalogs. Collapse infrastructure metadata and repeated attempts.
- Use existing recommendation admission and network epoch checks. An editor action must be labelled as an editor action; configuration changes need concrete review.
- Use measured byte and connection-stage evidence. Do not invent progress percentages or completion estimates.
- Mobbin references: [Quo](https://mobbin.com/screens/0a290bb8-a09e-4569-a5e6-d82ba3f1421d) for verdict and expandable metrics; [FotMob](https://mobbin.com/flows/7fb3b7bd-2ea6-4da2-9c74-de43c42f4e49) for step states; [Starlink](https://mobbin.com/screens/177843a6-2027-4a20-96e1-9fe9225053dc) for measured path comparison; [Jomo](https://mobbin.com/flows/c1bee2f1-34c1-40d2-a5f2-4fd073fcb38c) for concrete retry actions.

## Contracts and ownership

- Control writer: Home route/screen/mode card and state, scan actions/selection/section builder, and focused tests.
- Guided writer: Diagnostics route/screen/overview, MainViewModel and Home actions, persona/state models, navigation, and focused tests.
- Presentation writer: Scan section, matrix/transfer/stage/probe cards, sheets, evidence copy helpers, and focused tests.
- Root: all locale resources, translation manifest, planning/task state, Blockcheck classification, combined validation, review, and main integration.
- Writers use separate worktrees. Interface changes are agreed before edits. No shared locale, golden, native, dependency, JNI, protobuf, or storage edits by writers.

## Risks / Trade-offs

- Existing artifact producer may prevent an APK build. Report JVM/Compose checks separately from artifact and device acceptance.
- Parallel main changes can conflict. Fetch, rebase, and run combined gates before each fast-forward integration.
- Translated long labels can clip. Render narrow screens and large text with disposable output; do not bless screenshot fixtures.

## Migration Plan

No persistence migration is required. Existing evidence remains readable. Rollback is a revert of the relevant commit. Validate focused app and detection tests, app/service locale lint, static analysis, architecture health, translation export, and disposable Compose renders. Run device smoke if a device is available. Commit and push each finished unit after review; record any unverified artifact or device gate.
