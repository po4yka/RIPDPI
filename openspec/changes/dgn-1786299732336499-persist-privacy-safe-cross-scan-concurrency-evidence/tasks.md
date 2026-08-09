# DGN-1786299732336499: Persist privacy-safe cross-scan concurrency evidence

## Objective

Enable independent two-scan confirmation while retaining only bounded,
categorical, privacy-safe evidence through the complete diagnostics lifecycle.

## Ownership

Own diagnostics assessment/contracts, Room migration, retention/reset,
archive/backup projection, Kotlin surfaces, and focused tests. Serialize shared schemas.

## Execution

- [ ] DGN-1786299744030582 Define fresh independent cross-scan confirmation and privacy-safe history contracts #feature !high @item:DGN-1786299732336499
- [ ] DAT-1786299744063971 Persist bounded alias evidence with migration, retention, reset, backup, and restore semantics #feature !high @item:DGN-1786299732336499 @blocked_by:DGN-1786299744030582
- [ ] TST-1786299744097427 Prove independence, stale/partial/cancelled refusal, migration, reset, and raw-identifier absence #feature !high @item:DGN-1786299732336499 @blocked_by:DAT-1786299744063971
- [ ] DGN-1786299744121451 Surface confirmed and insufficient cross-scan evidence consistently in UI, archive, and export #feature !high @item:DGN-1786299732336499 @blocked_by:TST-1786299744097427

## Verification

Run focused Cargo/JVM/Room tests, schema and archive gates, privacy scans, and `just task-check`.
