# Change: Share AmneziaWG servers as full .conf files

Task ID: `UIX-1795661310559744`

## Why

The user can share a saved AmneziaWG server as a complete `.conf` file, from the same server actions menu that relay servers use.

## What Changes

- An exporter writes a saved AmneziaWG server as a `.conf` file that carries the interface, peer, and every AmneziaWG 3.x obfuscation field the server has.
- Importing an exported file gives a server equal to the original, for plain WireGuard, AmneziaWG 1.x, and AmneziaWG 3.x servers.
- AmneziaWG rows in the Servers list offer Share in the server actions menu and on long-press, like relay rows.
- Share for a subscription-owned AmneziaWG server follows the same rule as subscription-owned relay servers.

## Capabilities

### New Capabilities

- `uix-1795661310559744-share-amneziawg-servers`: Share AmneziaWG servers as full .conf files

### Modified Capabilities

- None.

## Impact

- Portfolio area: `ui`.
- Exact code, contracts, and gates are constrained by the linked task and design.
