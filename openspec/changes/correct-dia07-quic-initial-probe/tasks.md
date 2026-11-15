# DGN-1794734098746391

## Objective

Correct the scheduled v1 response and its transport for the eight audit findings.

## Ownership

The portfolio record specifies the separate packet, transport, Android, and integration worktrees and their paths.

## Execution

- [ ] DGN-1794734226758622 F1 Preserve typed first-response evidence #bug !high @item:DGN-1794734098746391
- [ ] DGN-1794734227385679 F2 Scope capability and failure evidence #bug !high @item:DGN-1794734098746391
- [ ] DGN-1794734227865118 F3 Receive complete UDP replies #bug !high @item:DGN-1794734098746391
- [ ] DGN-1794734228345800 F4 Validate Initial frames #bug !high @item:DGN-1794734098746391
- [ ] DGN-1794734228991845 F5 Stop expired and cancelled exchanges #bug !high @item:DGN-1794734098746391
- [ ] DGN-1794734229647891 F6 Repeat with a fresh packet number #bug !high @item:DGN-1794734098746391
- [ ] DGN-1794734230254826 F7 Bound and validate SOCKS associations #bug !high @item:DGN-1794734098746391
- [ ] DGN-1794734230729850 F8 Validate and normalize probe destinations #bug !high @item:DGN-1794734098746391

## Verification

Run the focused locked Rust tests, the owning Kotlin tests and the CI source gates on the integrated tree.
