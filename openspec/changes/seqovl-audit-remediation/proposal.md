# Change: Fix seqovl execution and fallback contracts

Task ID: `ENG-1794557051102648`

## Why

The DPI-12 audit found silent plain writes, false applied receipts, an accepted unusable chain, and unsafe or lost fallback decisions at the raw/helper boundary.

## What Changes

- Keep seqovl execution consistent when TCP flag overrides are present.
- Report the effective split family when raw overlap cannot run.
- BREAKING: reject a send step after terminal seqovl instead of accepting an unusable chain.
- Refuse oversized packets safely and preserve commit uncertainty across direct and helper paths.
- Require TCP repair and a usable raw IP family in capability reporting.

## Capabilities

### New Capabilities

- `seqovl-execution-safety`: Seqovl execution, validation, fallback and error safety.

### Modified Capabilities

- None.

## Impact

- Kotlin data validation and native config, desync, runtime, privileged-ops and root-helper crates.
- Additive helper error metadata; no new dependency, JNI method, protobuf field, production service or credential.
