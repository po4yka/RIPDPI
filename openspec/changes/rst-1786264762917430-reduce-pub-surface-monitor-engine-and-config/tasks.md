# RST-1786264762917430: Reduce pub surface of monitor-engine/config and add golden contracts for high-fan-in crates

## Objective

Reduce pub surface of monitor-engine/config and add golden contracts for high-fan-in crates

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [ ] RST-1786264762919211 ripdpi-monitor-engine pub-item count meaningfully reduced; no external consumer breaks #feature !low @item:RST-1786264762917430
- [ ] RST-1786264762919473 ripdpi-config lib.rs documents its true role #feature !low @item:RST-1786264762917430
- [ ] RST-1786264762919002 Golden-contract tests exist for ripdpi-failure-classifier and ripdpi-config public surfaces #feature !low @item:RST-1786264762917430
- [ ] RST-1786264762919715 cargo nextest run --locked green workspace-wide; clippy clean #feature !low @item:RST-1786264762917430

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
