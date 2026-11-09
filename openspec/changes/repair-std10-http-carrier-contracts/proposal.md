# Change: Repair HTTP relay carrier contracts

Task ID: `TRN-1794211839090466`

## Why

The STD-10 audit reproduced shared-carrier cancellation, missing HTTP capability checks, failed successful CONNECT responses, and invalid capsule admission. These failures affect existing relay modes.

## What Changes

- Keep active xHTTP streams when another request is cancelled.
- Require peer settings before MASQUE Extended CONNECT and HTTP Datagrams.
- Support valid H2 tunnel success statuses and reject invalid capsule input.
- Document the active Naive runtime selection.
- Restore missing build inputs required to run the affected native checks.
- No breaking configuration or JNI changes. Unsupported modes remain rejected.

## Capabilities

### New Capabilities

- `http-relay-carrier-contracts`: Correct admission, cancellation and framing for existing relay carriers.

### Modified Capabilities

- None.

## Impact

- ripdpi-masque, ripdpi-xhttp, existing h2/h3 backends, Naive documentation, and native verification.
- No new production package, external service, locale or wire schema.
