## Context

The pinned Xray XUDP packet reader creates a fresh packet for each response.
An omitted address leaves the packet address unset. Its SOCKS response path then
uses the original request header; an explicit response address overrides only
that packet. The current Rust last-target cache also misroutes an omitted reply
after a response from a different explicit address.

## Goals / Non-Goals

- Goal: bind omitted response sources to the accepted initial NEW target.
- Goal: preserve cancellation, bounded queues and malformed-frame rejection.
- Non-goal: change peer DNS rules, timeouts, dependencies or public configuration.

## Decisions

- Keep immutable initial request authority per VlessXudpSession, shared with its
  sole reader through an Arc and OnceLock. Each carrier starts without authority.
- Validate and encode the request before queue admission. After writer capacity
  is reserved, publish the first canonical target, enqueue the complete NEW frame
  and advance first_packet without an intervening await. Cancellation before
  reservation cannot seed authority. Never seed from subsequent uplink targets.
- Resolve omitted targets when a complete frame is decoded, not before awaiting
  frame bytes. This permits an immediate reply without a publication race.
- Remove sticky last-downlink target state. An explicit source applies to one
  datagram; every omitted source uses the initial NEW target. Missing authority
  remains an error. Keep session ID, options, payload and address validation.
- Preserve writer failure as terminal carrier closure and the existing queue,
  timeout, cancellation and task cleanup contracts.

## Contracts and ownership

- Protocol worker: native/rust/crates/ripdpi-vless/src/xudp.rs and adjacent
  native/rust/crates/ripdpi-vless/src/xudp/source_authority_tests.rs if needed.
  Update the existing sticky-source regression to the pinned peer contract.
- Coordinator: task/specification records and integration. Security worker:
  independent review. Android acceptance worker: sole physical device owner.
- No Kotlin, JNI, persisted schema, public signature, Cargo lock, peer fixture
  or shared version file changes are planned.

## Risks / Trade-offs

- Wrong source can deliver a reply to the wrong client destination. Test initial
  A, later uplink B, explicit reply C and omitted replies with independent values.
- An immediate response can race the writer acknowledgement. Use a real loopback
  carrier regression and resolve authority only when response metadata is ready.
- Cancelled admission must not publish authority. Exercise queue saturation,
  cancellation and a subsequent valid first request.

## Migration Plan

No data migration is needed. This corrects the internal wire interpretation;
there is no legacy sticky-source path. Source rollback uses normal reviewed Git
changes. No live rollout is included.

Run complete locked ripdpi-vless tests, workspace all-target Clippy with warnings
denied, format, architecture and task gates. Use the shared build gate. Preserve
current malformed-frame and cancellation tests. Require independent review,
hosted native gates, genuine APK preparation and all four authorized USB scenarios.
