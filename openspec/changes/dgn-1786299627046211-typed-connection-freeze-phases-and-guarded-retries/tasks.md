# DGN-1786299627046211: Implement typed connection-freeze phases and guarded retries

## Objective

Deliver typed freeze evidence end to end and a disabled-by-default runtime guard
that prevents confirmed freezes from causing unsafe retry selection.

## Ownership

Own classifier, diagnostics contracts/projections, runtime policy/configuration,
focused tests, and user-safe evidence. Serialize shared schemas and snapshots.

## Execution

- [ ] DGN-1786299671488858 Add typed freeze phase, direction, persistence, and unknown evidence without widening the block matrix #feature !high @item:DGN-1786299627046211
- [ ] RST-1786299671511424 Add optional confirmed-freeze guard state and suppress same-destination retry plus diversification #feature !high @item:DGN-1786299627046211 @blocked_by:DGN-1786299671488858
- [ ] TST-1786299671536591 Prove classification boundaries, serde compatibility, default no-op, expiry, and privacy behavior #feature !high @item:DGN-1786299627046211 @blocked_by:RST-1786299671511424
- [ ] DGN-1786299671561803 Surface bounded typed evidence in Kotlin, UI, archive, and export projections #feature !high @item:DGN-1786299627046211 @blocked_by:TST-1786299671536591

## Verification

Run focused Cargo/JVM tests, schema and API snapshot checks, privacy boundaries, and `just task-check`.
