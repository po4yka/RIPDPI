# TRN-1793634968382224: Bound XHTTP pools and prefer TCP reserves

## Objective

Preserve connection limits through native activation and verify TCP recovery from UDP payload failure.

## Ownership

One client writer owns core/data, core/engine-api, core/engine, core/service, native/rust/crates/ripdpi-relay-core, native/rust/crates/ripdpi-xhttp and app/subscription in this worktree. Deployment producer changes have a separate owner. No shared Cargo.lock or protobuf edits.

## Execution

- [x] TRN-1793635054587188 Preserve and enforce bounded XHTTP pools from bundle import to native sockets #feature !high @item:TRN-1793634968382224
- [x] TRN-1793635055012253 Prefer verified TCP reserves when a UDP carrier cannot transfer payloads #feature !high @item:TRN-1793634968382224
- [x] TRN-1793635055436981 Validate paired fixture, runtime boundaries and independent review #feature !high @item:TRN-1793634968382224

## Verification

Targeted import, persistence, wire DTO, selector and native tests; staticAnalysis; independent review. No live deployment or device mutation.
