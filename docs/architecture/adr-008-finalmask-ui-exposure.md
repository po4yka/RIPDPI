# ADR-008: Finalmask parameters are fully exposed in the UI — no action required

**Status:** Accepted  
**Date:** 2026-04-27  
**Scope:** `ripdpi-relay-core`, `app/.../ui/screens/config/RelayFields.kt`

## Context

A Phase 3 cleanup audit (P3.4) flagged `ResolvedRelayFinalmaskConfig` fields
`sudoku_seed` and `rand_range` as potentially missing from the UI, with the
hypothesis that users could only set them via the `chain_dsl` advanced
text-field.

The struct in question (defined in `native/rust/crates/ripdpi-relay-core/src/lib.rs`):

```rust
pub struct ResolvedRelayFinalmaskConfig {
    pub r#type: String,
    pub header_hex: String,
    pub trailer_hex: String,
    pub rand_range: String,
    pub sudoku_seed: String,
    pub fragment_packets: i32,
    pub fragment_min_bytes: i32,
    pub fragment_max_bytes: i32,
}
```

## Investigation Findings

A full-codebase search (`rg`, `fd`) revealed:

1. **All finalmask parameters are exposed as explicit UI fields** in
   `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/RelayFields.kt`:
   - `RelayFinalmaskTypeHeaderCustom` branch: `header_hex`, `trailer_hex`,
     `rand_range` fields
   - `RelayFinalmaskTypeSudoku` branch: `sudoku_seed` field
   - `RelayFinalmaskTypeFragment` branch: `fragment_packets`,
     `fragment_min_bytes`, `fragment_max_bytes` fields
   - `RelayFinalmaskTypeNoise` branch: `rand_range` field

2. The `chain_dsl` text-field is a **strategy chain DSL** (DPI engine
   configuration, not relay finalmask). It is entirely separate from relay
   finalmask and does not accept finalmask parameters.

3. There are **no orphan fields** — every field in `ResolvedRelayFinalmaskConfig`
   is both actively parsed in `ripdpi-xhttp/src/finalmask.rs` and reachable
   via the UI.

4. The `RelayKindFields` composable in `RelayFields.kt` already accepts typed
   callbacks (`onRelayFinalmaskSudokuSeedChanged`,
   `onRelayFinalmaskRandRangeChanged`, etc.) that are wired end-to-end through
   `ModeEditorScreen` to the view-model.

## Decision

No code change is required. The audit premise was incorrect.

This ADR is filed to:
- Close the P3.4 tracking item with evidence
- Prevent future re-audit of the same question
- Add a KDoc comment on `RelayKindFields` clarifying that finalmask parameter
  exposure is intentional and complete

## Consequences

- No migration, no proto change, no string resources added.
- A single KDoc block is added to `RelayFields.kt` for discoverability.
- `chain_dsl` field must **not** be extended with finalmask semantics; it
  controls only the DPI strategy chain.

## Alternatives Considered

| Variant | Rationale for rejection |
|---|---|
| A — add explicit UI fields | Already exist; this would be duplication |
| C — remove fields from struct | Fields are actively used by the runtime and fully reachable via UI |
