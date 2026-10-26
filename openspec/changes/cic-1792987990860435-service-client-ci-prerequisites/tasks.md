# CIC-1792987990860435: Restore service client CI prerequisites

## Objective

Compile the existing WebTunnel library without dev-feature unification and verify current Simple Home report and confirmation actions.

## Ownership

This worktree owns `native/rust/crates/ripdpi-webtunnel/Cargo.toml`, `app/src/testGithubSimple/kotlin/com/poyka/ripdpi/ui/screens/simple/SimpleHomeScreenTest.kt`, `scripts/guide/specs/ui-ux-audit.yaml`, `scripts/ci/native-size-baseline.json`, `docs/native/size-monitoring.md`, and the linked task/change records. Do not change runtime UI, shared dependency versions, or golden images.

## Execution

- [x] CIC-1792988158457520 Compile normal WebTunnel with required Tokio macros and verify the locked non-test build #bug !high @item:CIC-1792987990860435
- [x] CIC-1792988159191603 Verify current Simple Home confirmation and report actions with the complete regression class #bug !high @item:CIC-1792987990860435

- [x] CIC-1792994175016752 Include the existing subscription import page in the audited guide flow and verify its exact partition #bug !high @item:CIC-1792987990860435

- [x] CIC-1793000057523106 Apply the user-approved native size limit from digest-verified CI binaries while retaining all recorded measurements #bug !high @item:CIC-1792987990860435

## Verification

Run locked non-test WebTunnel checking, the complete Simple Home unit test class, guide generator unit tests, task validation, architecture health, and hosted CI before merge. No device or deployment acceptance is claimed.
