# DGN-1790430642039687: Correct QUIC and STUN reachability probes

## Objective

QUIC and Snowflake STUN verdicts use valid, matching network evidence.

## Ownership

The diagnostics network probe writer owns the QUIC and STUN Kotlin probe files, focused tests, and DPI suite wiring listed in `design.md`. Other writers own diagnostics finalization, export, and RKN paths.

## Execution

- [ ] DGN-1790430818948716 Replace synthetic QUIC production packets with native packets and test local build failure #bug !high @item:DGN-1790430642039687
- [ ] DGN-1790430824485948 Validate Snowflake STUN response type cookie transaction and source with focused tests #bug !high @item:DGN-1790430642039687

## Verification

Run focused `:core:diagnostics:testDebugUnitTest` tests with the repository's local native-build skip flag, then the module suite. Check app compilation when the local libXray AAR is available. Device and hosted CI evidence remain separate.
