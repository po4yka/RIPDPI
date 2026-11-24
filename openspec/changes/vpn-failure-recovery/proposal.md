# Change: Keep VPN blocking and recover after network return

Task ID: `VPN-1795489847968987`

## Why

Xray listener or engine failure and an unexpected proxy exit with code zero
can close the VPN despite the traffic-blocking setting. A VPN held in Failed
after handover retries cannot recover when the network returns.

## What Changes

- Apply the engine-failure setting to both exit paths.
- Retry an exhausted handover on the next usable network event while the VPN
  retains its TUN barrier. Publish Connected when forwarding starts again.

## Capabilities

### New Capabilities

- `vpn-failure-recovery`: consistent engine-failure blocking and recovery from a retained handover barrier.

### Modified Capabilities

- None.

## Impact

- Changes affect the Android service, runtime-state authority, and their tests. No breaking contract change.
