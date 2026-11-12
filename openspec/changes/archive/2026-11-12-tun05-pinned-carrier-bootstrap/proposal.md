# Change: Pin the WSS carrier dial addresses

Task ID: `RST-1794477836924583`

## Why

VPN recovery retains a blocking TUN. Reopening a hostname carrier through system DNS can depend on that TUN. Service bootstrap must supply underlay addresses while TLS keeps the URL identity.

## What Changes

- Accept optional numeric `carrierWsAddresses` on the native runtime DTO.
- Explicit candidate lists never fall back to system DNS; empty or invalid candidates fail before networking.
- Omitted fields preserve intentional legacy host bootstrap.
- No breaking wire or storage change.

## Capabilities

### New Capabilities

- `native-wss-bootstrap`: separate numeric dial targets from WSS identity.

### Modified Capabilities

- None.

## Impact

- Native AWG runtime and WSS connector. Service and Kotlin DTO provision addresses in the independent recovery change.
