# Change: Validate deployed AWG dual-stack addresses and MTU

Task ID: `DAT-1792081130689287`

## Why

Deployment now supplies one IPv4 and one IPv6 host address with a shared tunnel MTU. Permissive import can silently drop malformed addresses or substitute an MTU, separating the Android and native transport configuration.

## What Changes

- BREAKING: deployment bundle AWG entries require ordered IPv4 /32 and IPv6 /128 addresses and an integer MTU in 1280..1420.
- Reject unsafe activation MTUs before native startup; preserve accepted values exactly.
- Verify consumer behavior against mirrored deployment contracts and actual independent-peer traffic.
- Add a peer-owned validated task export so the producer can resolve this consumer and native historical receipts without imposing its task schema or rewriting client records.
- Require Android arm64 emulator native-core interop with reconnect and endpoint MTU coverage. Physical-device and Android VpnService TUN/no-leak gates are outside this change; the producer owns Linux forwarding/no-leak verification.

## Capabilities

### New Capabilities

- `awg-deployed-transport`: exact deployment transport admission and measured consumer interop.

### Modified Capabilities

None. Standalone INI address order and family support stay unchanged.

## Impact

`:core:data:runtime-state`, `:core:data`, `:core:service`, engine instrumentation, independent test peers, and the native task CLI federation adapter. No production dependency or wire version changes.
