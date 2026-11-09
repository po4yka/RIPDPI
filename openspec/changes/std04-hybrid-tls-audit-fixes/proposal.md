# Change: Correct hybrid TLS audit contracts

Task ID: `TRN-1794199027304873`

## Why

The STD-04 audit found an overwritten TLS alert and three misleading contracts. Malformed hybrid shares fail closed but report an internal error. Profile metadata claims a wire-size property that it does not test. REALITY override documentation omits its mandatory prefix, and fake-profile provenance names a legacy draft as ML-KEM.

## What Changes

- Preserve the alert selected by the key exchange backend.
- Report validated profile configuration without claiming a measured ClientHello size.
- Document the REALITY group prefix and correct the legacy capture name.
- Keep successful handshakes, group preferences, certificate checks, REALITY authentication, and packet bytes intact.

## Capabilities

### New Capabilities

- `tls-kem-audit-contracts`: TLS key-exchange failure and metadata accuracy requirements.

### Modified Capabilities

- None. Existing wire schemas and KEM configuration fields stay intact. The profile status value changes to the existing `satisfied` status to remove a false guarantee.

## Impact

Vendored BoringSSL alert handling, ripdpi-tls-profiles metadata/tests, and related source documentation. No new dependency, JNI/protobuf/storage migration, golden update, device operation, or deployment is needed.
