# Change: Repair TLS extension contracts

Task ID: `TRN-1794158969509887`

## Why

The STD-02 audit found incorrect ALPN in DoT, missing negotiated-protocol checks in HTTPS H2 carriers, and malformed SNI from accepted names. These defects break compatible local peers and name verification.

## What Changes

- Apply transport-specific ALPN and validate the selected protocol before application bytes.
- Validate and canonicalize TLS DNS/IP identity, keeping URL authority separate.
- Refuse invalid nonempty diagnostic TLS overrides if the reported substitution is confirmed.
- BREAKING: Names that cannot form valid ASCII SNI are rejected. Unicode URL hosts still use the URL parser's existing IDNA conversion.

## Capabilities

### New Capabilities

- `tls-extension-contracts`: TLS name and ALPN behavior for the existing clients.

### Modified Capabilities

- None.

## Impact

- Native TLS profiles, DNS, relay carriers, owned HTTP/WS clients and TLS diagnostics.
- Additive Rust helper APIs; no JNI, protobuf, stored configuration or dependency-version change.
- Separate commits for each confirmed problem. Main integration and origin push are authorized.
