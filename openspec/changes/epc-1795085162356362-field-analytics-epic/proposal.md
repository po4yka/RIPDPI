# Change: Deliver opt-in anonymous field analytics for network, blocking, and server health

Task ID: `EPC-1795085162356362`

## Why

RIPDPI measures network, blocking, and server health on the device (`core/diagnostics`, `core/detection`, `native/rust/crates/ripdpi-monitor-engine`, `FieldTelemetrySummary`) but nothing leaves the device and nothing tells operators which servers fail, where, or why. Failure classification is string-matched, the diagnostics export swallows errors, and the `simple` flavor embeds credential material. The owner decided on opt-in, anonymous, aggregated field reports so that analytics of networks, blocking, client-to-server connections, and server/VPN health become correct and complete without identifying users.

## What Changes

- Epic umbrella for four child changes: R1 (secrets out of shipped assets), R2 (vendored contracts and one failure-stage taxonomy), R3 (opt-in field report client), R4 (bundle v3 consumption with endpoint health).
- BREAKING: ripdpi-bundle v3 replaces v2; v2 bundles are rejected, with no compatibility shim.
- No user data leaves the device without an explicit, revocable opt-in.

## Capabilities

### New Capabilities

- `field-analytics-program`: program-level invariants that every child change must keep (consent, anonymity, contract ownership, ordering).

### Modified Capabilities

- None.

## Impact

- Kotlin: `core/diagnostics`, `core/diagnostics-data`, `core/detection`, `core/data`, `core/data/runtime-state`, `core/service`, `app`.
- Rust: `native/rust/crates/ripdpi-diagnostics-*`, `ripdpi-monitor-engine`.
- Contracts: `client-field-report` v1 and `ripdpi-bundle` v3, authored in the sibling `ripdpi-vpn-deploy` repository and vendored here with a drift pin.
- Counterpart `ripdpi-vpn-deploy` changes: D1 contracts, D2 ingest, D3 protocol probes, D4 bundle v3 generation, D5 block detection, D6 retention/alert delivery, D7 cross-repo e2e. D1 gates R2; D2 gates the live end of R3; D4 gates the live end of R4; D7 closes the program.
- AGENTS.md rule "user data leaves the device only through explicit export" is the policy boundary; R3 must show that opt-in upload is consistent with it (owner decision, recorded in R3 design).
