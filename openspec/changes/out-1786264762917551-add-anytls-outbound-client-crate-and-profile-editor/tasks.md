# OUT-1786264762917551: Verify AnyTLS interoperability with upstream anytls-go

## Objective

Prove byte-level client interoperability against a pinned upstream `anytls-go` server without reopening completed editor or import work.

## Ownership

- `native/rust/crates/ripdpi-anytls/**`
- pinned upstream interoperability fixture and evidence

## Execution

- [x] OUT-1786264762917091 Implement AnyTLS framing, padding, and TLS-session tests #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917103 Wire relay-core TCP and UDP-over-TCP AnyTLS backends #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917403 Import anytls, Clash, and Sing-box profiles #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917988 Carry password and root-certificate fields through native config #feature @item:OUT-1786264762917551
- [ ] OUT-1786264762917903 Run pinned upstream anytls-go TCP and UDP-over-TCP interoperability and record exact versions and evidence #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917396 Reject unsupported server-side fallback targets explicitly #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917776 Validate AnyTLS password, server, port, and SNI in the dedicated editor #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917917 Confirm the dedicated editor and import/profile paths are the canonical editing surface #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917429 Publish AnyTLS strategy-pack compatibility hints #feature @item:OUT-1786264762917551
- [x] OUT-1786264762917479 Redact AnyTLS password and root certificate from diagnostics #feature @item:OUT-1786264762917551

## Verification

- `cargo nextest -p ripdpi-anytls -p ripdpi-relay-core`
- pinned upstream `anytls-go` interoperability lane with exact server/client revisions
