## Context

The audit confirmed French resource defects and raw values in app diagnostic presentation. The exact baseline and scope are in proposal.md and the portfolio task.

## Goals / Non-Goals

- Goal: Correct the confirmed text and display paths with resource and behavior evidence.
- Non-goal: Change uncertain terminology, raw evidence, protocols, stored data, schemas, dependencies or baselines.

## Decisions

- One writer owns all locale resources and the export manifest. Translate new RTT/debug captions in all ten locales.
- Reuse existing state and unknown-label resources. Use one display mapping for known states and RTT bands across Live and History. Preserve unsupported custom values.
- Use the existing selected-locale formatter for debug durations. Keep raw debug stage identifiers exact.
- The current proxy converter sets the TCP protocol mask from HTTP/HTTPS only. The runtime predicate applies no L7 filter when the mask is zero; UDP groups are configured separately. Clarify the existing hint in all ten locales. No toggle semantics change.
- Test behavior through real presentation factories and retained samples. Render French warnings and recovery controls at normal and large font sizes. Existing golden fixtures remain read-only until file-specific user approval.

## Contracts and ownership

- Only app presentation and resource contracts change. Core models and Rust crates retain their behavior. No JNI, protobuf, storage, configuration or wire migration.
- Resource, runtime, tests and integration ownership and isolated worktrees are recorded in the portfolio task. All locale XML and the manifest have one writer.
- Existing RDS components and styling remain unchanged. The diagnostics/home/settings preview specifications are references; only text and display values change.

## Risks / Trade-offs

- Longer French text can clip: verify visibility and bounds and inspect rendered images.
- Existing fixtures can expose intended display changes: inspect each diff and require narrow approval before blessing.
- Simple test inputs must be real: never count a placeholder relay bundle as acceptance evidence.
- Standalone resource parity does not prove correct language: check the audited key set, action references and semantic text.

## Migration Plan

No data migration or breaking change. Revert the scoped commits to roll back. Run resource regressions, parity/export, targeted RED/GREEN behavior tests, full app tests, staticAnalysis, app/service lint, French renders and existing screenshot checks. Before integration run architecture health and locked Cargo metadata. Fetch/rebase origin/main, rerun combined gates, integrate with fast-forward, push main and wait for hosted CI. Keep local, CI and device evidence separate.
