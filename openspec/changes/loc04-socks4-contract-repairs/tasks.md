# PRX-1794116241356560

## Objective

Repair all six LOC-04 findings with observed regression evidence.

## Ownership

See the portfolio task for exact worker paths. Root owns all planning and integration files.

## Execution

- [ ] PRX-1794116315304352 Repair F1 CONNECT success ordering and verify failure paths #bug !high @item:PRX-1794116241356560
- [ ] PRX-1794116317212493 Repair F2 directional FIN handling and verify duplex paths #bug !high @item:PRX-1794116241356560
- [ ] PRX-1794116318838623 Repair F3 IPv6 policy enforcement and verify both families #bug !high @item:PRX-1794116241356560
- [ ] PRX-1794116320267158 Repair F4 DNS cancellation and handshake deadline ownership #bug !high @item:PRX-1794116241356560
- [ ] PRX-1794116322076440 Repair F5 request parser contracts and sentinel classification #bug !high @item:PRX-1794116241356560
- [ ] PRX-1794116324708518 Repair F6 size rejection replies and verify request limits #bug !high @item:PRX-1794116241356560
- [ ] PRX-1794116326351813 Review combined changes and run required integration gates #bug !high @item:PRX-1794116241356560

## Verification

Run targeted and affected-crate Rust tests, cargo fmt, clippy, just ci-gates, architecture health, locked metadata, and independent review.
