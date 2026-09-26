# DGN-1790433260349809: Correct diagnostics recommendation and regression selection

## Objective

Use all relevant probe outcomes and the last completed home run.

## Ownership

This writer owns the recommendation engines, home run order wiring, and focused tests in `:core:diagnostics`. Other writers own export, data stores, DPI probes, and RKN.

## Execution

## Verification

Run focused red and green unit tests, the full `:core:diagnostics:testDebugUnitTest` gate with the local native-build skip flag, strict OpenSpec validation, and `./taskctl validate`.
- [ ] DGN-1790433444778107 Include TCP threshold freeze in strategy recommendation #bug !high @item:DGN-1790433260349809
- [ ] DGN-1790433446065936 Use completion order for previous home run #bug !high @item:DGN-1790433260349809
- [ ] DGN-1790433447391364 Continue past suppressed DNS trigger #bug !high @item:DGN-1790433260349809
