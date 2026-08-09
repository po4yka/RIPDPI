# OUT-1786264762917513: Complete Mieru UDP carrier and upstream interoperability

## Objective

Make every selectable Mieru carrier truthful and prove the existing TCP/mux path against upstream `mita`.

## Ownership

- `native/rust/crates/ripdpi-mieru/**`
- Mieru profile validation and pinned upstream fixture

## Execution

- [ ] OUT-1786266573979348 Verify TCP and multiplexed Mieru sessions against a pinned upstream mita server #feature !high @item:OUT-1786264762917513
- [ ] OUT-1786266573979902 Implement and verify the UI-selectable Mieru UDP carrier or remove UDP from the public profile contract #feature !high @item:OUT-1786264762917513
- [x] OUT-1786264762917550 Implement nonce-safe low, middle, and high multiplexing #feature @item:OUT-1786264762917513
- [x] OUT-1786264762917605 Validate Mieru server, credentials, carrier, multiplexing, and MTU #feature @item:OUT-1786264762917513
- [x] OUT-1786264762917618 Use the shared monotonic network-time anchor #feature @item:OUT-1786264762917513
- [x] OUT-1786264762917419 Redact Mieru credentials from diagnostics #feature @item:OUT-1786264762917513
- [x] OUT-1786264762917565 Parse and emit Mieru share profiles #feature @item:OUT-1786264762917513

## Verification

- `cargo nextest -p ripdpi-mieru -p ripdpi-network-time`
- pinned upstream `mita` TCP, mux, and UDP interoperability lane
