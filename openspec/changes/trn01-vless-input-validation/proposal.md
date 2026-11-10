# Change: Reject invalid VLESS addons and unsupported origin flows

Task ID: `TRN-1794335331879799`

## Why

The TRN-01 audit found that all VLESS decoders skip addons without protobuf validation. The Cloudflare origin also loses the decoded Flow and accepts unsupported body modes. A malformed peer can reach payload delivery, and an incompatible authenticated request can reach a target connection.

## What Changes

- Reject malformed protobuf addons before payload delivery in every request and response decoder.
- Preserve exact request Flow and reject nonempty Flow at the plain TCP origin before target connection or success.
- State the eager response helper cancellation contract and retain the stateful lazy driver.
- Keep valid unknown protobuf fields and existing canonical outbound bytes compatible.

## Capabilities

### New Capabilities

- `vless-wire-input-validation`: bounded addon decoding, flow admission and partial-I/O behavior.

### Modified Capabilities

- None. No persisted, JNI, configuration or on-wire schema changes. The internal Rust decoded-header struct gains the already-defined Flow value; malformed and unsupported inputs now fail.

## Impact

- `ripdpi-vless`, `ripdpi-cloudflare-origin`, and tests of direct consumers.
- No new production dependencies or changes to upstream pins, credentials, Android UI, baselines or release artifacts.
