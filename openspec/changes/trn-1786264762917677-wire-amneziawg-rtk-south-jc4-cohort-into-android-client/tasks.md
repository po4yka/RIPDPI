# TRN-1786264762917677: Verify AmneziaWG interoperability and tune RTK-South retries

## Objective

Prove the landed standalone AWG path against an external peer and derive a bounded retry policy from observed RTK-South handshakes.

## Ownership

- AmneziaWG Android/native runtime and external interoperability fixture
- retry policy and exact-device evidence

## Execution

- [x] TRN-1786264762919013 Build AmneziaWG client support for all Android ABIs #feature @item:TRN-1786264762917677
- [x] TRN-1786264762919672 Import and persist cohort parameters and credentials #feature @item:TRN-1786264762917677
- [ ] TRN-1786264762919567 Prove Android interoperability against an external AWG endpoint with RTK-South parameters #feature @item:TRN-1786264762917677
- [ ] TRN-1786264762919854 Derive and regression-test a bounded configurable retry budget from observed handshake evidence #feature @item:TRN-1786264762917677 @blocked_by:TRN-1786264762919567
- [x] TRN-1786264762919975 Keep packet randomization distinct from WireGuard-over-WebSocket transport #feature @item:TRN-1786264762917677

## Verification

- all-ABI native build and existing AWG protocol tests
- exact Android artifact external-peer smoke and retry timing receipt
