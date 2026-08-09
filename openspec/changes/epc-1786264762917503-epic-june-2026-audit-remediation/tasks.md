# EPC-1786264762917503: Epic - June 2026 full-project audit remediation

## Objective

Finish the three evidence-backed audit remnants that still change code or a required gate.

## Ownership

- `CIC-1786272446167159`: active architecture-health regression
- `RLY-1786264762917178`: RelayBackend exhaustiveness defect
- `RST-1786264762917099`: two unconsumed native crates

## Execution

- [ ] EPC-1786272743768392 Restore the required architecture-health gate on main #epic @item:EPC-1786264762917503
- [ ] EPC-1786272743770245 Close the RelayBackend exhaustiveness defect #epic @item:EPC-1786264762917503
- [ ] EPC-1786272743771933 Remove the two proven unconsumed native crates and refresh architecture docs #epic @item:EPC-1786264762917503

## Verification

- child task gates and exact-main CI
- `python3 scripts/ci/check_architecture_health.py`
- native workspace metadata/contracts after crate removal
