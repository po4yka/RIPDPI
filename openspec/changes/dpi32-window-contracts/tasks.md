# PRX-1794677617943037

## Execution

- [ ] PRX-1794677889882075 Correct the hint contract and preserve automatic settings #bug !high @item:PRX-1794677617943037
- [ ] PRX-1794677895341059 Reject overflowing and invalid socket window values #bug !high @item:PRX-1794677617943037
- [ ] PRX-1794677902771029 Record applied and degraded socket option outcomes #bug !high @item:PRX-1794677617943037
- [ ] PRX-1794677906705947 Reject unsupported production window YAML steps #bug !high @item:PRX-1794677617943037
- [ ] PRX-1794677909411409 Verify negotiated positive windows with an independent oracle #bug !high @item:PRX-1794677617943037
- [ ] PRX-1794677915743236 Review combined repairs and run required integration gates #bug !high @item:PRX-1794677617943037

## Ownership

See design.md for exact writer lanes and the serialized socket-options handoff.

## Verification

Use build-gate --lane ripdpi, isolated Cargo targets, --locked, and at most four build workers. Run the owning Rust suites, Kotlin settings writer test, independent review, just ci-gates, architecture health and locked metadata. Capture/device/external acceptance remains separate.
