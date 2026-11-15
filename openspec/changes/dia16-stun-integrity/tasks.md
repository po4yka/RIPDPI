# DGN-1794747967332880

## Objective

Correct F1–F7 with observed regressions and one commit per finding.

## Ownership

See docs/tasks/issues/fix-dia16-stun.md for isolated worktrees and shared section owners.

## Execution

- [x] DGN-1794748226407078 F1 honor the network master switch for WebRTC STUN #bug !high @item:DGN-1794747967332880
- [x] DGN-1794748227184168 F2 record actual default path ownership and capabilities #bug !high @item:DGN-1794747967332880
- [x] DGN-1794748228335587 F3 validate complete STUN messages and address attributes #bug !high @item:DGN-1794747967332880
- [x] DGN-1794748229393696 F4 match UDP peers and validate SOCKS5 envelopes #bug !high @item:DGN-1794747967332880
- [x] DGN-1794748230661569 F5 keep path divergence separate from leak proof #bug !high @item:DGN-1794747967332880
- [x] DGN-1794748232537445 F6 show explicit incomplete evaluation outcomes #bug !high @item:DGN-1794747967332880
- [x] DGN-1794748233510189 F7 close cancelled probes within bounded budgets #bug !high @item:DGN-1794747967332880

## Verification

Run owning regression tests, core/detection unit tests, affected app tests, staticAnalysis, just ci-gates, architecture health and locked Cargo metadata. Use local loopback fixtures. Review each behavior diff before commit.
