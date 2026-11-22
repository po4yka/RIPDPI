# Compare verified HTTPS transfers with ECH and GREASE

Task ID: `DGN-1795331701582623`

## Why

A TLS handshake alone does not show that HTTPS data can cross the path. A separate ECH GREASE control helps locate the stage where a transfer fails.

## What Changes

- Add a manual comparison of TLS 1.3, ECH GREASE, and real ECH on a fixed HTTPS endpoint and path.
- Verify the response size and SHA-256 in each mode. Report setup, handshake, and transfer outcomes separately.

## Capabilities

### New Capabilities

- `ech-transfer-comparison` — Three bounded TLS 1.3 transfers to the same endpoint.

### Modified Capabilities

- None.

## Impact

Android diagnostics, the verified transfer runner, and the diagnostics TLS profiles.
