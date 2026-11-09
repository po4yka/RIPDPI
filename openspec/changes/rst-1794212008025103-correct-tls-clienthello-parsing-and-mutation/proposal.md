# Change: Correct TLS ClientHello parsing and mutation

Task ID: `RST-1794212008025103`

## Why

ClientHello inspection can use bytes outside SNI and miss hosts across records. SNI mutation can discard a later record or place padding after pre_shared_key.

## What Changes

- Bound SNI inspection to its vectors and handle fragmented ClientHello records in consumers.
- Preserve trailing records and TLS 1.3 extension ordering during mutation, or reject the input unchanged.
- Resolve the fuzz lock and type ECH request IDs at the Rust boundary without changing JNI signatures or invalid-ID behavior.
- No intended breaking configuration, storage, JNI, or protobuf change.

## Capabilities

### New Capabilities

- `tls-clienthello`: Correct ClientHello inspection and mutation.

### Modified Capabilities

- None.

## Impact

- Rust packets and proxy strategy consumers. No new dependency or migration.
