# PRX-1794118814905211

## Execution

- [x] PRX-1794118934207394 Fix F05 header octet parsing and Basic compatibility with regressions #bug !high @item:PRX-1794118814905211
- [x] PRX-1794118934771703 Fix F02 invalid CONNECT framing with regressions #bug !high @item:PRX-1794118814905211
- [x] PRX-1794118935386499 Fix F03 HTTP field grammar with regressions #bug !high @item:PRX-1794118814905211
- [x] PRX-1794118935968965 Fix F04 HTTP 1.0 Host compatibility with regressions #bug !high @item:PRX-1794118814905211
- [x] PRX-1794118936564724 Fix F06 decimal authority port validation with regressions #bug !high @item:PRX-1794118814905211
- [x] PRX-1794118937259707 Fix F01 upstream success ordering with loopback regressions #bug !high @item:PRX-1794118814905211
- [x] PRX-1794118938174073 Fix F07 excessive header response with loopback regressions #bug !high @item:PRX-1794118814905211
- [x] PRX-1794118938962416 Review combined fixes and run source gates before integration #bug !high @item:PRX-1794118814905211

## Ownership

Use the serialized path lanes in docs/tasks/issues/loc05-http-connect.md.

## Verification

Affected Rust tests and Clippy, formatting, locked metadata, architecture health, just ci-gates and independent per-fix review.
