# TRN-1786264762917184: Per-exit-IP TLS cap with true mux-preference in relay-core backend

## Objective

Per-exit-IP TLS cap with true mux-preference in relay-core backend

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] TRN-1786264762919669 Cap physical VLESS+Reality port-443 TLS carriers per resolved exit IP; reject a ninth non-mux carrier until a slot is released #feature @item:TRN-1786264762917184
- [x] TRN-1786264762919309 Verify that nine concurrent logical streams on a mux-enabled backend reuse one cached carrier through RelayMux::open_stream #feature @item:TRN-1786264762917184
- [x] TRN-1786264762919414 Share one limiter implementation while keeping proxy direct-path and relay carrier counter instances independent #feature @item:TRN-1786264762917184
- [x] TRN-1786264762919606 cargo nextest run -p ripdpi-relay-core -p ripdpi-relay-mux --locked green; clippy clean; pr-reviewer pass (hot path) #feature @item:TRN-1786264762917184

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
