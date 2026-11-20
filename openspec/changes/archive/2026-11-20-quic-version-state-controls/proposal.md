# Change: Isolate QUIC version diagnostics from residual flow state

Task ID: `DGN-1795014101783145`

## Why

FOCI 2026 reports QUIC filtering in Russia that depends on the version and on earlier packets of the same flow. DIA-07 (`DGN-1794734098746391`) sends v1 Initials only and excludes v2 by design. The existing version-negotiation decoys use unknown versions, so they are not a v2 test. A controlled v1/v2 matrix on isolated flows tells the user whether a QUIC failure is version-specific before they change a QUIC strategy. Priority is high because QUIC strategies already ship and this result changes how their failures are read.

## What Changes

- Add a manual, opt-in matrix of v1 and v2 Initials for a control and a test SNI against one user-authorized endpoint.
- Build both Initials with the existing `ripdpi-packets` builders.
- Run each cell on a fresh socket and record execution order; run a same-flow residual cell only when the user enables it.
- Report Initial-level evidence only.

## Capabilities

### New Capabilities

- `quic-version-state-controls`: v1/v2 Initial matrix with flow isolation.

### Modified Capabilities

- None. The matrix uses the DIA-07 probe path and does not change DIA-07 results.

## Impact

`ripdpi-packets` (tests only), `ripdpi-diagnostics-transport` UDP probing, the diagnostics wire contract and `EngineContract.kt`, `core/diagnostics` projection and redacted export, diagnostics UI and its strings in all locales. No new backend.

## Sources

https://petsymposium.org/foci/2026/foci-2026-0010.php and https://github.com/UPB-SysSec/RussiaQuicResults

The paper reports measurements from March to April 2026 at three vantage points. They are historical observations, not a permanent property of any provider.
