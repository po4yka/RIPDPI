# Preserve ECH comparison failure reasons

Task ID: `DGN-1795351267598237`

## Why

The ECH transfer comparison combines DNS failure with unusable configuration and loses the reason for authenticated ECH rejection. An explicit matching port excludes a usable HTTPS record.

## What Changes

Retain distinct reasons in all result surfaces. Accept the HTTPS record when its explicit port matches the selected endpoint.

## Capabilities

### New Capabilities

- ech-comparison-failure-reasons

### Modified Capabilities

- None.

## Impact

Encrypted DNS selection, TLS diagnostics, and Android comparison results.
