# OUT-1793433244049580: Implement the Mieru UDP carrier

## Objective

Run the Mieru outbound over its UDP transport and prove interoperability with the pinned upstream server.

## Ownership

Single writer for `native/rust/crates/ripdpi-mieru`, the Mieru facade in `ripdpi-relay-tls-transports`, the Mieru rows of the relay capability registries, and the Mieru interop oracle and runner. Registry rows for other relay kinds are out of scope.

## Execution

- [ ] OUT-1793433300726405 Transcribe the pinned upstream UDP underlay and session ARQ semantics into PROTOCOL.md #feature @item:OUT-1793433244049580
- [ ] OUT-1793433302840461 Implement the UDP segment codec, protected UDP underlay and session ARQ with loss and reordering tests #feature @item:OUT-1793433244049580
- [ ] OUT-1793433304790845 Add UDP cases to the upstream Mieru oracle, pass them, then enable Mieru UDP in the capability registries #feature @item:OUT-1793433244049580
