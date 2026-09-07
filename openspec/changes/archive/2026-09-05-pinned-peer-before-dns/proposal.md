# Change: Attempt pinned peers before DNS fallback

Task ID: `DGN-1788599171554142`

## Why

Eager fallback DNS can exhaust a scan deadline before a valid configured IP is tried. The audit introduced this ordering into SOCKS5 UDP; direct TCP and UDP already had the same weakness.

## What Changes

- Try pinned peers before resolving fallback names.
- Retain hostname fallback after pinned attempts fail, within the existing scan deadline.
- No breaking API, wire, configuration, or schema changes.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `connectivity-protocol-integrity`: pinned address attempts precede fallback DNS.

## Impact

- `ripdpi-diagnostics-transport`: candidate iteration for direct TCP/UDP and SOCKS5 UDP, including route experiments. The runner retains first-candidate informational resolution so it does not eagerly resolve fallback names before transport attempts. No new dependencies.
