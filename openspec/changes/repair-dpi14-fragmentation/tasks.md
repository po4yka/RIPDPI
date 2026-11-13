# RST-1794557321446019: Repair DPI-14 fragmentation

## Objective

Resolve F1-F9 with preserved packet and send-progress contracts.

## Ownership

See design.md and the portfolio ownership section. Every writer has a separate worktree. Helper protocol and version changes have one owner.

## Execution

## Verification

Run affected Rust tests with --locked, Kotlin chain validation tests, packet reassembly oracle, protocol/API source gates, cross-compilation when available, independent review, and combined just ci-gates. Do not activate root or send external probes.
- [x] RST-1794557459134025 Fix F1 chain isolation and test Kotlin and Rust validation #bug !high @item:RST-1794557321446019
- [x] RST-1794557459773519 Fix F2 TCP repair send queue accounting #bug !high @item:RST-1794557321446019
- [x] RST-1794557460478261 Fix F3 uncertain fragment send progress and replay safety #bug !high @item:RST-1794557321446019
- [x] RST-1794557461255765 Fix F4 round and internal split eligibility #bug !high @item:RST-1794557321446019
- [x] RST-1794557462331060 Fix F5 IPv6 profiles and helper v4 contract #bug !high @item:RST-1794557321446019
- [x] RST-1794557463263982 Fix F6 typed helper failure metadata #bug !high @item:RST-1794557321446019
- [x] RST-1794557464023170 Fix F7 pre-send privilege and PMTU fallback #bug !high @item:RST-1794557321446019
- [x] RST-1794557464717320 Fix F8 fragment identification reservations #bug !high @item:RST-1794557321446019
- [x] RST-1794557465604641 Fix F9 original IP packet length limits #bug !high @item:RST-1794557321446019
- [x] RST-1794557466416688 Review combined changes and run required validation gates #chore !high @item:RST-1794557321446019
