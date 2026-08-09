# RST-1786264762917044: Add optional Cloudflare Workers transport mode

## Objective

Add optional Cloudflare Workers transport mode

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [ ] RST-1786264762917807 Operator-supplied Worker URL + auth bearer is consumable via core:data:model typed schema #feature @item:RST-1786264762917044
- [ ] RST-1786264762917191 WS-tunnel transport variant routes through the Worker, using the Worker hostname for SNI and TLS, the real target in a X-Ripdpi-Upstream header #feature @item:RST-1786264762917044
- [ ] RST-1786264762917435 At least one reference Worker script under docs/native/cloudflare-workers/relay.js that operators can deploy #feature @item:RST-1786264762917044
- [ ] RST-1786264762917161 Loopback test (against a mock HTTP/2 server) exercises the Worker-routed path #feature @item:RST-1786264762917044
- [ ] RST-1786264762917285 docs/native/cloudflare-tunnel-operations.md documents deployment, cost model, and rate-limit considerations #feature @item:RST-1786264762917044

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
