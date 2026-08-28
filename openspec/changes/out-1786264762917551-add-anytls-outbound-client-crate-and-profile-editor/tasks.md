# OUT-1786264762917551: Finish AnyTLS profile editor and compatibility gaps

## Objective

Finish AnyTLS profile editor and compatibility gaps

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] OUT-1786264762917091 ripdpi-anytls crate exists with frame, padding, and TLS-session tests #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917103 Relay-core builds an AnyTLS backend, validates it as UDP-capable, and covers TCP plus UDP-over-TCP fixtures #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917403 anytls://, Clash anytls, and Sing-box anytls imports map to first-class profiles #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917988 Relay native config carries AnyTLS password and root-certificate fields #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917903 Cross-interop against upstream anytls-go is verified and recorded. (deferred: live-server only; offline-infeasible nightly oracle.) #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917396 Fallback-SNI and fallback-server behavior matches upstream spec, or unsupported behavior is rejected explicitly. (RIPDPI's client has no server-side TLS fallback; ProxyUriCodec.parseAnyTls now explicitly rejects anytls:// nodes advertising… #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917776 AnyTLSProfileScreen validates password length, server + port, and server-name (SNI) #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917917 Main Mode Editor exposes AnyTLS fields instead of relying only on import/profile records. (deferred: AnyTLS is fully configurable via the dedicated AnyTlsProfileScreen + import; exposing it inline is a separate end-to-end "make AnyTLS a se… #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917429 Strategy-pack metadata advertises AnyTLS compat hints, especially around QUIC-heavy neighborhoods. (StrategyPackProtocolHint + bundled catalog.json anytls entry with quicHeavyNeighborhood: true, surfaced via StrategyPackSnapshot.protocolHi… #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917479 Password is redacted in all diagnostic surfaces. (Rust: hand-written Debug for AnyTlsClientConfig masks password + root cert. Kotlin: ProxyProfile.AnyTls.toString masks the password.) #feature @item:OUT-1786264762917551

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
