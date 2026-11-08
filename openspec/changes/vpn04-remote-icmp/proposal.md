# Change: Forward remote UDP errors to TUN

Task ID: `VPN-1794144829236231`

## Why

The TUN emits local setup failures but cannot receive remote UDP ICMP errors through standard SOCKS5. Applications therefore miss port-unreachable and path-MTU feedback even when the local proxy receives it.

## What Changes

- Forward proven remote IPv4 and IPv6 UDP errors through an authenticated private local association.
- Preserve each original TUN quote and the ICMP source, type, code and MTU.
- Keep standard SOCKS5 clients compatible and reject untrusted or ambiguous feedback.

## Capabilities

### New Capabilities

- `vpn-remote-udp-errors`: negotiated local transport and kernel-to-TUN attribution.

### Modified Capabilities

- None. Existing local setup error behavior remains valid.

## Impact

- Rust SOCKS codec, proxy runtime/platform adapter and tunnel core.
- New private wire contract; no JNI, settings, storage or Android Builder change.
- No new external dependency, backend, privilege or persistence.
