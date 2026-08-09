# TRN-1786264762917184: Per-exit-IP TLS cap with true mux-preference in relay-core backend

## Objective

Per-exit-IP TLS cap with true mux-preference in relay-core backend

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [ ] TRN-1786264762919669 Per-exit-IP concurrent-session cap enforced on the relay-core foreign-exit path (the path that actually opens VLESS+Reality+Vision TLS sessions) #feature @item:TRN-1786264762917184
- [ ] TRN-1786264762919309 At cap, the next stream reuses an existing muxed session via RelayMux::openstream (true mux-preference), verified by a test #feature @item:TRN-1786264762917184
- [ ] TRN-1786264762919414 No double-counting between the proxy-runtime direct-path gate and the relay-core cap #feature @item:TRN-1786264762917184
- [ ] TRN-1786264762919606 cargo nextest run -p ripdpi-relay-core -p ripdpi-relay-mux --locked green; clippy clean; pr-reviewer pass (hot path) #feature @item:TRN-1786264762917184

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
