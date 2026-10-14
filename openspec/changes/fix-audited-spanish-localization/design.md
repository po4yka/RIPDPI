## Context

The audit baseline is main at 2f6803083d74880f3c47e209aa83dfe9d6953e33. Keys are complete but Spanish copy and dynamic labels have confirmed defects.

## Goals / Non-Goals

- Goal: Correct every confirmed audit finding and prove locale contracts and presentation behavior.
- Non-goal: Change optional terminology, network behavior, dependencies, layouts, baselines, or golden fixtures.

## Decisions

- Map known enum and snapshot labels at the app presentation boundary. Keep raw and unknown values unchanged.
- Use existing matching resources where possible. New keys land in all ten locales together.
- Read the selected locale when numbers are formatted; do not cache a language choice or modify exported formats.
- One resource writer owns all XML, the manifest, and resource tests. Other writers supply exact key requests.

## Contracts and ownership

- App owns diagnostic, history, and card presentation. Service owns notification number presentation.
- Rust, JNI, protobuf, wire codes, storage schemas, and service lifecycle remain unchanged. No migration is required.
- Writer worktrees and serialized paths are recorded in the portfolio task.

## Risks / Trade-offs

- Unknown evidence must remain exact: test fallback preservation.
- Text can wrap differently: render affected Spanish scenes and inspect images without blessing fixtures.
- App language can differ from system language: test Spanish, English, and a language switch.

## Migration Plan

Run resource regressions, locale parity/export, targeted presentation tests, full app/service unit tests, staticAnalysis, app/service lint, architecture health, file limits, locked Cargo metadata, and task validation. Use build-gate, one Gradle worker, and no native APK build. Fetch and rebase before main integration, then rerun combined gates. Revert scoped commits to roll back. Hosted CI, native-speaker review, and physical-device acceptance remain separate evidence.
