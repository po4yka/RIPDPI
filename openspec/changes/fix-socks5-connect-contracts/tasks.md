# PRX-1794071497680636

## Ownership

See design.md for exclusive agent paths and root-owned shared planning files.

## Execution

- [ ] PRX-1794071600786245 Fix premature CONNECT success and verify delayed routing #bug !high @item:PRX-1794071497680636
- [ ] PRX-1794071601228418 Reject invalid SOCKS5 request headers with regression coverage #bug !high @item:PRX-1794071497680636
- [ ] PRX-1794071601781187 Return unsupported address-type replies with regression coverage #bug !high @item:PRX-1794071497680636
- [ ] PRX-1794071602368425 Return typed ruleset-denial replies with regression coverage #bug !high @item:PRX-1794071497680636
- [ ] PRX-1794071602984411 Reject empty authentication fields with regression coverage #bug !high @item:PRX-1794071497680636

## Verification

Run affected crate tests with --locked, read-only review, just ci-gates, architecture-health check, and locked metadata.
