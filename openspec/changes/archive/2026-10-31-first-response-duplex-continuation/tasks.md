# PRX-1792849175606125

## Objective

Complete uploads during first-response observation without replaying unretained continuation.

## Ownership

- Protocol worker: native/rust/crates/ripdpi-proxy-runtime/src/runtime/relay first_exchange.rs, failure_retry/first_outbound and response handling, session.rs, execution.rs, stream_copy and stream_copy_uring.rs, plus matching runtime tests.
- Protocol worker: native/rust/crates/ripdpi-proxy-runtime/src/runtime/state/listener.rs owner socket cancellation. Use independent write/read shutdown at this registry and equivalent explicit abort sites touched in the relay change. Do not change normal half-close, drain deadlines or the desync write contract.
- Coordinator: this task, proposal, specs, design, verification and integration. Security worker: independent replay, byte-ownership and lifecycle review. Android acceptance worker: sole physical-device operations.
- No changes to dependencies, public schemas, Hysteria protocol, fixtures or timeouts. Shared Cargo lock and serialized public contracts remain outside this change.
- Android acceptance worker owns the Linux CI integration in `.github/workflows/ci.yml`, its bounded runner script and matching script contract tests. Protocol worker owns the matching runtime regression and evidence that the io_uring path actually ran without capability fallback. The coordinator integrates both changes after independent review.

## Execution

- [x] PRX-1792849486028429 Implement bounded duplex continuation and preserve first response observation on both copy paths #bug !high @item:PRX-1792849175606125
- [x] PRX-1792849486474789 Verify no replay after continuation and cover early response half close and cancellation regressions #bug !high @item:PRX-1792849175606125
- [x] PRX-1792849486914254 Integrate reviewed source and verify rebuilt physical Hysteria upload with hosted native gates #bug !high @item:PRX-1792849175606125

## Verification

- Preserve the failing split-header 1 MiB real-proxy upload as a deterministic regression; require exact origin length and digest with autolearn enabled.
- Cover early response under backpressure, no replay after continuation, first-payload-only retry, fragmented response bytes, half-close and owner cancellation.
- Run complete affected native tests and workspace Clippy through build-gate, plus format and architecture checks. Verify the io_uring path through the existing Linux coverage; macOS evidence does not prove it.
- Prepare genuine Android artifacts and run the authorized Hysteria upload and automatic-recovery scenario. Keep exact-source hosted, device and artifact evidence separate.
