# TRN-1794466458131158

## Objective

Repair one finding per atomic commit with observable regression evidence.

## Ownership

Follow the exact lanes in design.md and the portfolio record. Coordinator owns
all shared task state and generated contract outputs.

## Execution

- [x] TRN-1794466908354230 F1 preserve WireGuard packets and WARP reserved MACs with real peer regressions #bug !high @item:TRN-1794466458131158
- [x] TRN-1794466913001475 F2 preserve canonical preset enable state across UI import and native mapping #bug !high @item:TRN-1794466458131158
- [x] TRN-1794466918147610 F3 label unselected WARP hints as recommendations with a semantic UI regression #bug !high @item:TRN-1794466458131158
- [x] TRN-1794466921219277 H2 refresh hints on observed network changes without reloading routing catalogs #bug !high @item:TRN-1794466458131158
- [ ] TRN-1794466927512271 Review and validate each fix then integrate main and push the reviewed commits #bug !high @item:TRN-1794466458131158

## Verification

Run focused Rust and settings/engine/app tests, UI semantic/render checks,
staticAnalysis, ci-gates, architecture health, locked metadata and task validation.
