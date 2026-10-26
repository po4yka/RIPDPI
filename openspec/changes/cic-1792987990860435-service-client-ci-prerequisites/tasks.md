# CIC-1792987990860435: Restore service client CI prerequisites

## Objective

Compile the existing WebTunnel library without dev-feature unification and verify current Simple Home report and confirmation actions.

## Ownership

This worktree owns `native/rust/crates/ripdpi-webtunnel/Cargo.toml`, `app/src/testGithubSimple/kotlin/com/poyka/ripdpi/ui/screens/simple/SimpleHomeScreenTest.kt`, and the linked task/change records. Do not change runtime UI, shared dependency versions, or golden images.

## Execution

- [ ] CIC-1792988158457520 Compile normal WebTunnel with required Tokio macros and verify the locked non-test build #bug !high @item:CIC-1792987990860435
- [ ] CIC-1792988159191603 Verify current Simple Home confirmation and report actions with the complete regression class #bug !high @item:CIC-1792987990860435

## Verification

Run locked non-test WebTunnel checking, the complete Simple Home unit test class, task validation, architecture health, and hosted CI before merge. No device or deployment acceptance is claimed.
