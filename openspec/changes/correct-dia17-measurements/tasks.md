# DGN-1794747077897183

## Ownership

Integration owns shared models, all locales, report/export status and gates. ui-worker owns presenter/score; ping-worker owns process/parser; timing-worker owns connect timing. Each uses an isolated worktree.

## Execution

- [x] DGN-1794747257208254 Fix F1 master network permission in presenter and verify flag boundaries #bug !high @item:DGN-1794747077897183
- [x] DGN-1794747257764556 Fix F2 incomplete measurement status through models classifiers UI and exports #bug !high @item:DGN-1794747077897183
- [x] DGN-1794747258258394 Fix F3 timing evidence projection and preserve local capability facts #bug !high @item:DGN-1794747077897183
- [x] DGN-1794747258765077 Fix F4 RTT cohort coverage jitter and partial loss confidence #bug !high @item:DGN-1794747077897183
- [x] DGN-1794747259325105 Fix F5 TCP connect measurement boundary and verify local timing #bug !high @item:DGN-1794747077897183
- [x] DGN-1794747259831498 Fix F6 cancellation and cleanup for owned ping processes #bug !high @item:DGN-1794747077897183
- [x] DGN-1794747260358533 Fix F7 numeric ping profile compatibility without external probes #bug !high @item:DGN-1794747077897183
- [x] DGN-1794747260917594 Run combined JVM lint source gates independent review and integrate #bug !high @item:DGN-1794747077897183

## Verification

Run owning JVM regressions, affected app tests, lint, just ci-gates and independent review. Preserve device and external coverage gaps.
