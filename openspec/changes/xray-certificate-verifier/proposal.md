# Change: Rebuild Xray with the patched certificate verifier

Task ID: `TRN-1792776009699531`

## Why

The native producer uses Go 1.27.2. Go 1.27.3 fixes CVE-2026-111943 by rejecting certificate chains that contain distrusted intermediate public keys. The artifact gate currently accepts any Go version string.

## What Changes

- Build the existing stable Xray sources with exact Go 1.27.3.
- Require that compiler in artifact metadata and embedded native build information.
- BREAKING: reject artifacts built with another compiler; consumers must rebuild.

## Capabilities

### New Capabilities

- `transport/xray-certificate-verifier`: patched compiler and verified native provenance.

### Modified Capabilities

- None.

## Impact

Native build scripts, CI producer, artifact verifier, packaging tests, and build instructions. Xray source versions, VPN protection patches, Kotlin, Rust, and server pins stay unchanged.
