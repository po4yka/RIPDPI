# TRN-1786264762917677: Wire AmneziaWG RTK South cohort (Jc=4) into Android client

## Objective

Wire AmneziaWG RTK South cohort (Jc=4) into Android client

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] TRN-1786264762919013 AmneziaWG client support compiles for all 4 Android ABIs #feature @item:TRN-1786264762917677
- [x] TRN-1786264762919672 Cohort profile import populates Jc/Jmin/Jmax/S/H/I from server-provided YAML or subscription URL #feature @item:TRN-1786264762917677
- [ ] TRN-1786264762919567 Smoke test against synthetic AWG endpoint with RTK South parameters succeeds #feature @item:TRN-1786264762917677
- [ ] TRN-1786264762919854 Probabilistic-retry logic implemented (max 4 attempts, configurable per-cohort) #feature @item:TRN-1786264762917677
- [x] TRN-1786264762919975 Dedup confirmed: distinct from add-wireguard-over-websocket-transport-amneziawg-disguise — this task wires AmneziaWG packet-signature randomization (Jc/Jmin/Jmax/H/S/I) into the existing ripdpi-warp-core WG kernel; the other adds a WG-over… #feature @item:TRN-1786264762917677

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
