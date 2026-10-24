# RST-1792838334204120

## Objective

Start and rebind Salamander endpoints without a blocking-socket panic.

## Ownership

Runtime worker: Salamander constructor, `tls_quic.rs` regression tests, task/spec. Integration owner: generated board integration, Android artifact, and physical acceptance.

## Execution

- [x] RST-1792838410157683 Enable nonblocking mode in Salamander socket admission and preserve bound protected socket ownership #bug !high @item:RST-1792838334204120
- [x] RST-1792838411619909 Verify real async datagrams and endpoint rebind with complete Hysteria crate tests and static checks #bug !high @item:RST-1792838334204120
- [x] RST-1792838413137335 Integrate reviewed source and verify physical Hysteria payload and UDP traffic on rebuilt Android artifact #bug !high @item:RST-1792838334204120

## Verification

Run locked complete Hysteria tests and Clippy under the shared build gate, formatting, task validation, independent review, hosted checks, and physical artifact acceptance.
