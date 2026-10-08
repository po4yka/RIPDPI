# Change: Declare QUIC Initial probe measurement scope

Task ID: `DGN-1791484611611171`

## Why

A valid response to a QUIC Initial is not an HTTP/3 application test.

## What Changes

- Include the measured scope and absent HTTP/3 validation in every probe result.

## Capabilities

### New Capabilities

- `quic-initial-evidence`: explicit probe scope.

### Modified Capabilities

- None.

## Impact

Native QUIC connectivity and strategy probes and their tests. Additive detail keys only; no versioned wire shape changes.
