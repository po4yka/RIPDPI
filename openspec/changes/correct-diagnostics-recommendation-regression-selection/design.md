# Design

Task ID: `DGN-1790433260349809`

## Context

The Rust TCP runner emits `tcp_freeze_after_threshold`; the Kotlin outcome taxonomy handles it, but strategy recommendation does not. Resolver recommendation selects one trigger before checking its authority. Home run outcomes have no completion timestamp, and the current in-memory map has no order.

## Decision

Map TCP freeze to the existing threshold-block category. Scan DNS trigger results in source order and skip only those suppressed by a healthy primary result for the same target. Track the last successfully published home run in memory, independently from the lookup map, and use it as the previous run. Exclude the current run ID from the predecessor lookup.

## Ownership and validation

This writer owns the recommendation engines, home run order wiring, and focused tests. Other diagnostics writers own export, data stores, DPI probes, and RKN. Each correction gets a failing focused test before implementation and a separate code commit. Run the full diagnostics unit suite and task contracts after the fixes.
