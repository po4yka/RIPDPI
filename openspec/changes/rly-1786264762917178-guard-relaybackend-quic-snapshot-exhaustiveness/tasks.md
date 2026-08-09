# RLY-1786264762917178: Guard RelayBackend manual match arms against silently-omitted QUIC variants

## Objective

Guard RelayBackend manual match arms against silently-omitted QUIC variants

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [ ] RLY-1786264762918568 PR confirms current 14-variant shape and the three manual-match sites #feature !low @item:RLY-1786264762917178
- [ ] RLY-1786264762918786 Adding a new RelayBackend variant now fails to compile until the QUIC/chain/UDP snapshot matches are updated (no silent (None, None)) #feature !low @item:RLY-1786264762917178
- [ ] RLY-1786264762918615 cargo nextest run -p ripdpi-relay-core --locked green; clippy clean #feature !low @item:RLY-1786264762917178

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
