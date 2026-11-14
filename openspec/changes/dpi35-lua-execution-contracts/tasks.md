# RST-1794684393149595

## Objective

Repair the six confirmed DPI-35 findings with tests and separate code commits.

## Ownership

The portfolio records writer paths and serialized lib.rs integration. Primary owns the combined tree and evidence. No dependency, locale, baseline or schema update is planned.

## Execution

- [ ] RST-1794684567619271 Reject external binary chunks and verify text loading #bug !high @item:RST-1794684393149595
- [ ] RST-1794684588025982 Materialize payload MODIFY results with packet regressions #bug !high @item:RST-1794684393149595
- [ ] RST-1794684595798085 Keep admitted flow state through capacity pressure #bug !high @item:RST-1794684393149595
- [ ] RST-1794684604990553 Save and load complete bundled Lua dependencies #bug !high @item:RST-1794684393149595
- [ ] RST-1794684608788278 Fail TUN activation on invalid Lua and extract owner assets #bug !high @item:RST-1794684393149595
- [ ] RST-1794684611101060 Return ABI-compatible clock values #bug !high @item:RST-1794684393149595
- [ ] RST-1794684615407297 Review and validate the combined repairs before integration #bug !high @item:RST-1794684393149595
## Verification

Run owning Rust regression suites and affected Kotlin tests/lint. Run independent review, just ci-gates, architecture health and locked Cargo metadata before main integration. Device/root and hosted CI evidence remain separate from local proof.
