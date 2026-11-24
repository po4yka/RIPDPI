# Change: Reduce VPN handover disruption and DNS recovery delay

Task ID: `VPN-1795491626935120`

## Why

VPN can restart both components after a metadata-only network change. The
handover completion label also conflates runtime startup with observed
forwarding. DNS recovery can miss several failures in one telemetry sample
and wait ten minutes after candidate exhaustion.

## What Changes

- Keep a usable native proxy on the same physical route when its applied
  inputs match. Refresh the tunnel only if it needs a different configuration.
- Report runtime readiness separately from observed forwarding.
- Count DNS failures across each telemetry interval and retry exhausted
  alternatives after a bounded, shorter pause.

## Capabilities

### New Capabilities

- `vpn-handover-dns-stability`: non-disruptive metadata refresh, honest handover status, and bounded DNS recovery.

### Modified Capabilities

- None.

## Impact

Android service, transient physical-network observations, runtime telemetry,
and its Android consumers. The tunnel snapshot adds an optional resolver failure
counter. JNI signatures and persisted configuration stay unchanged.
