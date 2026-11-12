# Change: Repair Snowflake source and bridge contracts

Task ID: `RLY-1794491312461911`

## Why

TUN-08 found that the pinned helper ignores the promised DTLS mimic setting and that accepted Tor bridge profiles can omit a required rendezvous method. Main already rejects raw PT as standalone application egress.

## What Changes

- Build Snowflake from an official pinned client that implements the configured DTLS policy; keep obfs4 independent.
- Reject missing, invalid or conflicting Snowflake rendezvous configuration before persistence or activation.
- Preserve Tor-only activation and identify local versus Android/external evidence.
- BREAKING: invalid saved bridge lines need explicit valid rendezvous parameters before activation. Stored records are not rewritten.

## Capabilities

### New Capabilities

- `snowflake-client-contract`: source provenance, hardened client configuration and valid rendezvous.

### Modified Capabilities

- None. Existing Tor PT activation policy remains unchanged.

## Impact

- PT source manifest, helper build/test evidence, settings validation, app/service regression tests and Snowflake ADR.
- No JNI, protobuf, wire version, stored schema, locale or baseline change.
