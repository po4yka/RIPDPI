# Change: Bound DummyPrepend work and repair its contracts

Task ID: `ENG-1794637889757963`

## Why

DPI-17 accepts unbounded packet counts and builds the full prelude before forwarding the original datagram. Its UI describes non-QUIC traffic, while its wire regression still expects an obsolete 64-byte datagram.

## What Changes

- BREAKING: Reject DummyPrepend counts above 64, including a total above 64 across one UDP chain.
- Bound direct-model planner work and generated datagram size while retaining the original payload.
- Describe the QUIC-only prerequisite in all ten locales.
- Check the current packet contract through a real loopback UDP receiver.
- Complete JVM tests and Android lint, verify CI and CodeQL, and repair required build incompatibilities without disabling checks.

## Capabilities

### New Capabilities

- `dummy-prepend-safety`: Bound DummyPrepend work and state its user and wire contracts.

### Modified Capabilities

- None.

## Impact

- Kotlin data/model validation and locale resources.
- Native config validation, DummyPrepend planner and owning tests.
- Proxy runtime wire regression test. No dependency or runtime/wire schema field changes.
- Build-logic patch verification and provenance for the existing OBFS4 asset.
- Add sourcePatchSha256 to build metadata. Reject old Lyrebird prebuilt receipts that do not identify the patch.
