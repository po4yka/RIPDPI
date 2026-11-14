# ENG-1794638026548237

## Ownership

Use the exact per-writer paths in docs/tasks/issues/fix-quic-crypto-split-audit-findings.md.

## Execution

- [x] ENG-1794638148243320 Fix F1 original delivery and fallback evidence #bug !high @item:ENG-1794638026548237
- [x] ENG-1794638149872072 Fix F2 fixed TTL projection #bug !high @item:ENG-1794638026548237
- [x] ENG-1794638151583022 Fix F3 conflicting CRYPTO overlap #bug !high @item:ENG-1794638026548237
- [x] ENG-1794638155153185 Fix F4 bounded split counts #bug !high @item:ENG-1794638026548237
- [x] ENG-1794638165336906 Fix F5 minimum Initial datagram input #bug !high @item:ENG-1794638026548237
- [x] ENG-1794638177788370 Verify combined changes and integrate #bug !high @item:ENG-1794638026548237

## Verification

Run owning Rust tests with --locked, Kotlin data tests, independent loopback byte decoding, read-only review, just ci-gates, architecture health and locked metadata. Record hosted CI separately.
- [x] ENG-1794653810860147 Repair minimum-size QUIC test seeds and verify hosted regressions #bug !high @item:ENG-1794638026548237
- [ ] ENG-1794658594940488 Align Xray acceptance version with the packaged libXray pin #bug @item:ENG-1794638026548237
