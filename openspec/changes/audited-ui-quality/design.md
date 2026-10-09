## Context

Task ID: `UIX-1791538891055420`. Findings are based on source inspection of main c2baf9931. Existing diagnostics UX integration is already in main. The connection redesign is a separate reviewed change and is rebased onto current main.

## Decisions

Use existing RDS components, typography, motion, and SecureWindowEffect. Preserve native, service, schema, storage, and navigation contracts. Use the smallest regression repair for each finding. Remove unsupported trend labels instead of inventing new evidence. Keep read-only capability information separate from selectable actions.

Shared writer owns focus targets, loading names, consent checkbox, dialog scroll, warning dismiss target, and accordion layout/motion. Setup writer owns credential masking/security leases, route switch semantics, subscription busy state, editor error recovery, remembered-network loading, and read-only Xray capabilities. Diagnostics writer owns profile result scoping, consent duplicate control, scan profile action gating, tuner feedback, history trend removal, and tool state resource mappings. Integration owns locale resources and all task/spec files. Writers cannot change golden fixtures or another lane.

Matching RDS references are the component and screen previews under docs/design/rds/preview. The persisted deck stays read-only. Repairs use current executable theme and component contracts where prose differs. Existing Mobbin connection references support action/status separation; they do not establish usability acceptance for every screen.

## Validation

Add meaningful failing regressions before each fix, then run affected app unit tests. Use build-gate for all heavy Gradle commands, four workers, native CPU budget four, and explicit JVM limits. Run app and service locale lint, staticAnalysis, architecture health, locked Cargo metadata, and applicable golden verification on the combined tree. Preview rendering must report the actual SDK; SDK 36 compatibility images do not establish SDK 37 acceptance. Native-less UI checks do not prove APK or traffic acceptance.

## Delivery and rollback

Implement in isolated worktrees. Review scoped commits, fetch and rebase origin/main, run combined gates, fast-forward main, and push, as authorized by the user. A normal revert of a scoped commit restores that repair. No schema migration is required. Do not change quality baselines to pass.
