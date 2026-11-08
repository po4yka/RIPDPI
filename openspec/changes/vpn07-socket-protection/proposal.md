# Change: Repair VPN outbound socket protection

Task ID: `VPN-1794152756242597`

## Why

The VPN-07 audit found missing proxy protection policy, unbounded local protection handshakes, inconsistent teardown, an unprotected profile-check caller, and UDP protection after bind. These paths need one consistent fail-closed contract.

## What Changes

- Apply VPN protection to proxy and profile-check outbound sockets.
- Bound local protection handshakes and close transferred descriptors on failure.
- Preserve native cleanup ownership until workers stop.
- Protect UDP sockets before bind and preserve inactive and loopback behavior.
- Keep one fix commit for each finding. No wire format, dependency version or package inventory change is planned. Existing workspace dependency edges may be added where an unbound socket is required.

## Capabilities

### New Capabilities

- `vpn-socket-protection`: VPN socket policy, bounded protection exchange and cleanup ownership.

### Modified Capabilities

- None.

## Impact

- Android service session and profile-check code, native proxy configuration, local UDS clients, and UDP carrier factories.
- Existing JNI signatures and SCM_RIGHTS one-byte ACK protocol stay compatible. No root or backend requirement is added.
