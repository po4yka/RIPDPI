## Context

The audit baseline is 17172d4a1fe3bfc02faf6366efad868da2ad3dce. String keys are complete, but generic array labels bypass translation guards and several production mappers return English.

## Goals / Non-Goals

- Goal: Correct every confirmed audit finding and prove resource and presentation behavior.
- Non-goal: Change the three disputed DNS terms, layouts, wire values, schemas, dependencies, quality baselines, or unrelated golden fixtures. The user authorized only the Arabic metric screenshot fixture after review of its locale-number change.

## Decisions

- Make descriptive source array items reference translated string keys. Keep the 13 exact technical and brand choices and all entry values intact.
- Read generic array values through TypedArray so integer values coerce to their exact string form. Recycle each array. Preserve all stored values and their order.
- Map known DPI and PT results and built-in host-pack IDs at the app presentation boundary. Keep raw evidence and unknown pack descriptions exact.
- Keep optional resource IDs alongside PT display strings so retained models resolve known labels in the current Compose locale. Models with custom text keep the existing string fallback.
- Read the selected locale at each catalog date presentation. Do not cache the system locale or change exported formats.
- A single resource writer owns all ten locale XML sets and the manifest. Kotlin writers send exact resource requests to that writer.
- Extend resource guard coverage to source array display references and verify that they resolve in all locales.

## Contracts and ownership

- App owns the changed UI and diagnostic presentation. The service asset remains an exact source catalog; app presentation owns translated known descriptions.
- No Rust, JNI, protobuf, wire, storage, activation, or lifecycle contract changes. No migration is required.
- Writer paths and serialized locale ownership are recorded in UIX-1791880509070975.

## Risks / Trade-offs

- Unknown or custom evidence can be changed by broad mapping: test fallback preservation.
- New text can wrap differently: inspect Hindi renders at normal and enlarged font sizes without blessing Hindi fixtures. Refresh only the user-authorized Arabic metric fixture through its narrow owning record command.
- App and system languages can differ: test Hindi on an English system and an English/Hindi/English language change.
- Keep native-speaker, hosted CI, and physical-device acceptance separate from local evidence.

## Migration Plan

Run Hindi resource regressions, all-locale contracts, parity/export, targeted and full app/service unit tests, staticAnalysis, app/service lint, architecture health, file limits, locked Cargo metadata, task contracts, and independent review. All compiler-backed checks use build-gate and one Gradle worker. Fetch and rebase on origin/main, rerun combined gates, merge main with ff-only, then push. Revert the scoped commits to roll back. No golden or baseline update is required.
