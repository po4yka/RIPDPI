# Diagnose HTTPS downloads that stall with larger bodies

Task ID: `DGN-1795361889557414`

## Why

A short verified response can succeed while a larger download stalls on the same path.

## What Changes

Add a manual RAW scan that compares two verified HTTPS downloads from one origin.

## Capabilities

### New Capabilities

- transfer-volume-comparison

### Modified Capabilities

- None.

## Impact

Manual diagnostics input, native TCP transfers, and saved diagnostics results.
