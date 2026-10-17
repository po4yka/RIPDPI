## Context

Task UIX-1792259264757428 adds user control to the existing platform-based haptic mechanism. The theme uses MainStartupState. Simple has no settings NavHost. Existing lifecycle event channels consume events once; state-backed VPN and Detection effects violate that model.

## Goals / Non-Goals

- Goal: persist System default / Off and deliver actual results once.
- Non-goal: custom vibration waveforms, system setting changes, network or native changes, new service result protocols, physical actuator certification.

## Decisions

- Add bool haptic_feedback_disabled at the next live free protobuf tag (413 at planning). False preserves legacy behavior. Mirror it in JSON FULL snapshots and canonical defaults.
- Pass the preference through startup state into RipDpiTheme. AND it with the current motion haptics gate. Keep View.performHapticFeedback and its system opt-out.
- Reuse a compact dropdown in Full Interface settings and a Simple interface overlay. Existing RDS settings row/dropdown are the reference; no dedicated haptics deck sheet exists.
- Connection arms only an explicit start and consumes it on actual Running. Rejection, timeout and cancellation clear it. Initial Running is quiet. Reuse the existing one-shot error signal to avoid duplicate errors.
- Detection emits terminal events from the coordinator and ViewModel. UI consumes them through LifecycleEventEffect. Remove effects from retained result rendering and VPN consent state.
- Preserve diagnostics session completion events. Add regression coverage for equal runs, restored results, lifecycle consumption and Off consumption.

## Contracts and ownership

- Affected modules: app, core:data:model and core:data:settings. No Rust crate, JNI or wire contract changes.
- Root serializes proto, defaults, JSON snapshots/mappers, locale sets, translation export, startup/theme settings and UI. The result worker owns event producers/consumers and direct tests in a separate worktree; root owns shared MainUiModels/MainActivityContent integration. Device specialist owns one new instrumented test. Review is read-only.
- Backup retains the new value in FULL and excludes settings in SHARE. Reset uses canonical defaults. Domain-wide Android backup exclusions remain intact.

## Risks / Trade-offs

- Status lacks an operation ID. Test pending-attempt invalidation against late status; do not change service authority or claim protocol-level correlation.
- A result produced while the UI is stopped queues under the existing convention and is consumed once on resume. Off suppresses delivery at consumption, so re-enabling never replays consumed results.
- Device tests count calls and check preference persistence; a physical device is required to assess tactile quality.
- Additional settings rows may affect screenshots. Verify actual images; golden updates require separate approval and are not automatic.

## Migration Plan

The additive false-default field needs no data migration. Legacy JSON/protobuf retain system behavior. Rollback ignores the new field. Validate focused RED/GREEN tests, settings/backup tests, theme and lifecycle tests, Full/Simple builds and app/service lint, staticAnalysis, architecture health, locked Cargo metadata, task/OpenSpec contracts, independent review and leased API37 emulator acceptance. Rebase on origin/main, repeat combined gates, fast-forward main and push as authorized. Inspect exact-source CI separately.
