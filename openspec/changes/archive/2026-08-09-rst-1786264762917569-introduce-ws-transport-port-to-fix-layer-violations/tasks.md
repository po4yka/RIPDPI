# RST-1786264762917569: Introduce a WsTransport port to fix L6/L4 -> L7 dependencies on ripdpi-ws-tunnel

## Objective

Introduce a WsTransport port to fix L6/L4 -> L7 dependencies on ripdpi-ws-tunnel

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- RST-1786264762918873 DROPPED: PR confirms the two edges still exist in cargo metadata #feature @item:RST-1786264762917569
- RST-1786264762918882 DROPPED: New port crate defines the trait; ripdpi-ws-tunnel implements it #feature @item:RST-1786264762917569
- RST-1786264762918481 DROPPED: Neither ripdpi-ws-bootstrap nor ripdpi-diagnostics-telegram lists ripdpi-ws-tunnel as a direct dep afterward #feature @item:RST-1786264762917569
- RST-1786264762918579 DROPPED: arch-layer-auditor re-run reports R-1 and R-2 resolved, no new cycle #feature @item:RST-1786264762917569
- RST-1786264762918928 DROPPED: cargo nextest run --locked green for affected crates; cargo deny check clean #feature @item:RST-1786264762917569

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
