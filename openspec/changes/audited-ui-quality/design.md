## Context

Task ID: `UIX-1791538891055420`. Findings are based on source inspection of main c2baf9931. Existing diagnostics UX integration is already in main. The connection redesign is a separate reviewed change and is rebased onto current main.

## Decisions

Use existing RDS components, typography, motion, and SecureWindowEffect. Preserve native, service, schema, storage, and navigation contracts. Use the smallest regression repair for each finding. Remove unsupported trend labels instead of inventing new evidence. Keep read-only capability information separate from selectable actions.

Shared writer also owns HomeModeCard actions, RipDpiButtonStateTokens, and focused contrast tests. Shared writer owns focus targets, loading names, consent checkbox, dialog scroll, warning dismiss target, and accordion layout/motion. Setup writer owns credential masking/security leases, route switch semantics, subscription busy state, editor error recovery, remembered-network loading, and read-only Xray capabilities. Diagnostics writer owns profile result scoping, consent duplicate control, scan profile action gating, tuner feedback, history trend removal, and tool state resource mappings. Integration owns locale resources and all task/spec files. Writers cannot change golden fixtures or another lane.

Matching RDS references are the component and screen previews under docs/design/rds/preview. The persisted deck stays read-only. Repairs use current executable theme and component contracts where prose differs. Existing Mobbin connection references support action/status separation; they do not establish usability acceptance for every screen.

## Validation

Add meaningful failing regressions before each fix, then run affected app unit tests. Use build-gate for all heavy Gradle commands, four workers, native CPU budget four, and explicit JVM limits. Run app and service locale lint, staticAnalysis, architecture health, locked Cargo metadata, and applicable golden verification on the combined tree. Preview rendering must report the actual SDK; SDK 36 compatibility images do not establish SDK 37 acceptance. Native-less UI checks do not prove APK or traffic acceptance.

## Delivery and rollback

Implement in isolated worktrees. Review scoped commits, fetch and rebase origin/main, run combined gates, fast-forward main, and push, as authorized by the user. A normal revert of a scoped commit restores that repair. No schema migration is required. Do not change quality baselines to pass.


## Inspected Mobbin recovery references

Mobbin MCP returned three iOS screens on 2026-10-09. Their images were inspected. [CVS Health notification settings](https://mobbin.com/screens/b0057e07-cf67-485a-a74f-313a736d47c0) keeps settings visible beneath an error banner. [Todoist credential recovery](https://mobbin.com/screens/0187b4de-e2d2-4a17-856a-f1303dad82a9) offers an explicit corrective action in a dialog. [inDrive number change](https://mobbin.com/screens/526d48f8-1870-4d58-8939-e73b3da923b7) identifies a retry delay in a sheet.

The selected local pattern is an in-context error with a real retry and the current draft retained. The images show hierarchy only; they do not prove data retention or Android accessibility. Do not add a retry delay without a real runtime requirement. Preserve existing RDS styling.
