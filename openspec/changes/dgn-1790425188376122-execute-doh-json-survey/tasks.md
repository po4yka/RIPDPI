# DGN-1790425188376122: Execute DoH JSON survey in monitor engine

## Objective

Replace the pending monitor stage with measured JSON DoH results for selected DNS targets.

## Ownership

This worktree owns the Kotlin diagnostics planner and local-network admission, Rust diagnostics contracts, HTTP, probes, and monitor-engine files, task/OpenSpec artifacts, and affected documentation. Shared contract files have one writer.

## Execution

- [x] DGN-1790425344723583 Share JSON resolver panel and send TLS-verified JSON HTTP requests #feature !high @item:DGN-1790425188376122
- [ ] DGN-1790425345534382 Select and run the survey with deduplication and focused tests #feature !high @item:DGN-1790425188376122
- [ ] DGN-1790425346262481 Verify contracts, architecture, and documentation on integrated code #feature !high @item:DGN-1790425188376122

## Verification

Run focused `cargo test --locked` for changed crates, architecture health, Cargo metadata, strict OpenSpec and task validation. Record Android/CI availability separately.
