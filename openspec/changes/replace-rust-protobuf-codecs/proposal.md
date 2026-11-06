# Change: Replace manual Rust Protobuf codecs

Task ID: `RST-1793945484896628`

## Why

The GeoSite and gRPC Hunk parsers reject valid Protobuf messages with unknown fields. The Hunk parser also rejects an empty message and ignores the compression flag. Manual decoding duplicates an existing schema and omits standard validation.

## What Changes

- Use a library codec for both message formats.
- Accept standard field order, unknown fields, scalar replacement, and default values.
- Reject malformed input, unsupported gRPC compression, and incomplete frames at end of stream.
- Keep sing-box GeoSite parsing and application resource bounds.
- BREAKING: Replace parser-specific Rust error variants and make Hunk encoding report size failures. Update all callers.

## Capabilities

### New Capabilities

- `native/protobuf-codecs`: Standard GeoSite and Hunk message handling with bounded gRPC framing.

### Modified Capabilities

- None.

## Impact

- Rust crates ripdpi-geo and ripdpi-xhttp; shared Cargo dependencies and lockfile.
- Existing Kotlin geosite.proto remains the schema source. No field numbers, JNI contracts, or stored settings change.
- Local tests, CI gates, independent review, main integration, and remote push are required.
