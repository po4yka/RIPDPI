# RST-1786264762917044: Add optional Cloudflare Workers transport mode

## Objective

Add optional Cloudflare Workers transport mode

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] RST-1786264762917807 Add typed Worker URL/credential-reference settings plus Keystore-backed bearer resolution into the transient runtime config #feature @item:RST-1786264762917044
- [x] RST-1786264762917191 Route the optional Telegram WS tunnel through the verified Worker endpoint with bearer and canonical X-Ripdpi-Upstream headers; reject unsafe/fake-SNI combinations #feature @item:RST-1786264762917044
- [x] RST-1786264762917435 At least one reference Worker script under docs/native/cloudflare-workers/relay.js that operators can deploy #feature @item:RST-1786264762917044
- [x] RST-1786264762917161 Exercise the production RFC 6455 Worker route against a local TLS WebSocket edge and assert headers plus framed round-trip #feature @item:RST-1786264762917044
- [x] RST-1786264762917285 docs/native/cloudflare-tunnel-operations.md documents deployment, cost model, and rate-limit considerations #feature @item:RST-1786264762917044

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
