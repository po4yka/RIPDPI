# UIX-1795661310559744: Share AmneziaWG servers as full .conf files

## Objective

The user can share a saved AmneziaWG server as a complete `.conf` file, from the same server actions menu that relay servers use.

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md`.
- [ ] UIX-1795661355817953 An exporter writes a saved AmneziaWG server as a `.conf` file that carries the interface, peer, and every AmneziaWG 3.x obfuscation field the server has. #feature @item:UIX-1795661310559744
- [ ] UIX-1795661360308009 Importing an exported file gives a server equal to the original, for plain WireGuard, AmneziaWG 1.x, and AmneziaWG 3.x servers. #feature @item:UIX-1795661310559744
- [ ] UIX-1795661360751271 AmneziaWG rows in the Servers list offer Share in the server actions menu and on long-press, like relay rows. #feature @item:UIX-1795661310559744
- [ ] UIX-1795661361180909 Share for a subscription-owned AmneziaWG server follows the same rule as subscription-owned relay servers. #feature @item:UIX-1795661310559744
