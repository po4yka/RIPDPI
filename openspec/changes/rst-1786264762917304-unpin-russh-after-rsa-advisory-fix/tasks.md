# RST-1786264762917304: Unpin russh after rsa advisory fix

## Objective

Unpin russh after rsa advisory fix

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [ ] RST-1786264762919282 cargo deny check advisories exits 0 with the RUSTSEC-2023-0071 suppression removed from deny.toml #feature !low @item:RST-1786264762917304
- [ ] RST-1786264762919454 cargo nextest run -p ripdpi-ssh --locked green #feature !low @item:RST-1786264762917304
- [ ] RST-1786264762919575 cargo nextest run --workspace --locked green #feature !low @item:RST-1786264762917304
- [ ] RST-1786264762919026 The =0.62.5 exact pin is removed or updated in Cargo.toml #feature !low @item:RST-1786264762917304
- [ ] RST-1786264762919596 Commit message references the russh release that resolved the rsa dependency #feature !low @item:RST-1786264762917304

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
