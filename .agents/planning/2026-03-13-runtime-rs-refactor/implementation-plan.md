# Runtime Refactor Implementation Plan

## Checklist

- [ ] Step 1: Expand characterization coverage before moving code
- [ ] Step 2: Introduce the internal module scaffold and move shared state/listener primitives
- [ ] Step 3: Extract protocol dispatch and handshake helpers
- [ ] Step 4: Extract routing, adaptive, and retry glue
- [ ] Step 5: Extract UDP associate flow handling
- [ ] Step 6: Extract desync execution helpers
- [ ] Step 7: Extract relay and first-response handling
- [ ] Step 8: Thin `runtime.rs` to public API wiring and run final verification

1. Step 1: Expand characterization coverage before moving code

Objective:
Lock the current runtime behavior in tests before any extraction begins.

Implementation guidance:
Add missing behavior-focused tests around the existing monolith instead of refactoring first. Prioritize runtime entry points and invariants that will be hardest to reason about after movement.

Test requirements:

- add characterization tests for protocol reply bytes and delayed-connect behavior
- add targeted tests for route advancement telemetry on recoverable failures
- add targeted tests for UDP associate state transitions and host-aware routing
- add property-style tests for pure invariants such as UDP packet codec round-trip and TLS record chunk splitting

How it integrates with previous work:
This step builds directly on the currently passing unit and `network_e2e` baseline.

Demo:
The broader characterization suite passes against the unchanged `runtime.rs`, proving the current behavior is frozen before extraction.

2. Step 2: Introduce the internal module scaffold and move shared state/listener primitives

Objective:
Create the internal `runtime/` module structure and move the low-risk shared types first.

Implementation guidance:
Add `runtime/state.rs` and `runtime/listeners.rs`. Move `RuntimeState`, `RuntimeCleanup`, `ClientSlotGuard`, shared constants, `build_listener`, and the accept-loop implementation with minimal text edits. Keep the public API in `runtime.rs`, forwarding into the extracted listener module.

Test requirements:

- run all Step 1 characterization tests
- keep helper tests for `ClientSlotGuard`
- add one focused test for listener bootstrap behavior only if a clean seam appears without requiring heavy mocks

How it integrates with previous work:
This step creates the module skeleton that later extractions will target while keeping behavior unchanged.

Demo:
`runtime.rs` becomes smaller, listener startup/shutdown behavior is unchanged, and the characterization suite still passes.

3. Step 3: Extract protocol dispatch and handshake helpers

Objective:
Move protocol-specific client handling into `runtime/handshake.rs` without changing handshake bytes or timeout behavior.

Implementation guidance:
Extract `handle_client`, protocol handlers, request readers, `resolve_name`, `HandshakeKind`, `DelayConnect`, and `maybe_delay_connect` as one unit. Keep protocol parsing helpers close to their handlers.

Test requirements:

- preserve and extend tests for SOCKS5 request parsing, Shadowsocks target parsing, and handshake success replies
- add or strengthen tests that confirm `delay_conn` still replies before blocking on the first payload only when payload-aware routing requires it
- rerun end-to-end SOCKS5 TCP, HTTP CONNECT, and chained upstream tests

How it integrates with previous work:
The extracted handshake module should call into the still-existing routing and relay functions through unchanged signatures.

Demo:
All client entry protocols continue to connect and forward traffic exactly as before, but the protocol-handling code is isolated from listener setup.

4. Step 4: Extract routing, adaptive, and retry glue

Objective:
Separate connection orchestration from protocol parsing and relay execution.

Implementation guidance:
Move `select_route*`, `connect_target*`, route success/failure handling, cache/autolearn flush helpers, adaptive glue, and retry pacing glue into `routing.rs`, `adaptive.rs`, and `retry.rs`. Keep `runtime_policy.rs`, `adaptive_tuning.rs`, `adaptive_fake_ttl.rs`, and `retry_stealth.rs` unchanged unless a test gap requires a tiny companion helper.

Test requirements:

- add unit tests for runtime-specific routing glue, trigger-to-failure mapping, retry signature inputs, and candidate diversification behavior
- verify lock-sensitive paths still behave the same by rerunning characterization tests that exercise route advance and reconnect
- keep pure logic tests in adjacent modules green

How it integrates with previous work:
Handshake, UDP, and relay code will depend on the new routing/adaptive/retry modules through the same free-function style.

Demo:
Route selection, route advancement, adaptive note/resolve behavior, and retry pacing produce the same telemetry and recovery behavior as before.

5. Step 5: Extract UDP associate flow handling

Objective:
Move the UDP worker lifecycle and flow state into a dedicated module without disturbing TCP behavior.

Implementation guidance:
Extract `handle_socks5_udp_associate`, `build_udp_relay_socket`, `udp_associate_loop`, `expire_udp_flows`, `UdpFlowActivationState`, the UDP packet codec, and UDP-specific cache/desync helpers into `runtime/udp.rs`.

Test requirements:

- preserve the existing end-to-end SOCKS5 UDP round-trip and QUIC host-routing coverage
- add focused tests for `expire_udp_flows` so failure-to-retry behavior is covered without waiting 60 seconds in real time
- add property-style tests for UDP packet codec round-trip across representative address families and payload sizes

How it integrates with previous work:
`handshake.rs` should now delegate UDP associate handling entirely to `udp.rs`, while `udp.rs` uses the extracted routing/adaptive/retry/desync modules.

Demo:
SOCKS5 UDP associate continues to round-trip traffic and advance/retry flows correctly, but all UDP-specific code has left `runtime.rs`.

6. Step 6: Extract desync execution helpers

Objective:
Isolate TCP and UDP desync execution so the relay code no longer owns plan execution details.

Implementation guidance:
Move `activation_context_from_progress`, `send_with_group`, `should_desync_tcp`, `has_tcp_actions`, `requires_special_tcp_execution`, `execute_tcp_actions`, `execute_tcp_plan`, `execute_udp_actions`, `send_out_of_band`, `set_stream_ttl`, and UDP TTL helpers into `runtime/desync.rs`.

Test requirements:

- preserve current helper tests for desync action gating and special TCP execution detection
- add focused tests around plan-bound validation and fallback-to-raw-write behavior where practical
- rerun end-to-end TCP flows that exercise desync-capable groups

How it integrates with previous work:
Relay and UDP modules now call into a dedicated desync executor, but the executor still uses direct platform helpers and existing config objects.

Demo:
TCP and UDP flows still honor desync configuration exactly as before, while the execution logic is isolated from connection orchestration.

7. Step 7: Extract relay and first-response handling

Objective:
Move the highest-risk relay logic only after all supporting modules are in place and covered.

Implementation guidance:
Extract `relay`, `relay_streams`, `read_optional_first_request`, `read_first_response`, `TlsRecordTracker`, `FirstResponse`, `reconnect_target`, and the inbound/outbound copy loops into `runtime/relay.rs`. Keep buffer sizes, thread counts, shutdown ordering, and session tracking unchanged.

Test requirements:

- keep and expand tests around first-response timeout selection and TLS partial-record tracking
- add characterization for route advancement on first-response failures and successful recovery after reconnect
- rerun end-to-end fault scenarios and the critical `network_e2e` path

How it integrates with previous work:
Relay becomes the orchestrator over already-extracted routing and desync helpers rather than a home for those concerns.

Demo:
First-response inspection, reconnect, and steady-state relay continue to work unchanged, but the last large behavior block is no longer inside `runtime.rs`.

8. Step 8: Thin `runtime.rs` to public API wiring and run final verification

Objective:
Finish the decomposition, reduce `runtime.rs` to readable top-level wiring, and verify the refactor as a whole.

Implementation guidance:
Remove remaining moved helpers from `runtime.rs`, keep only module declarations plus the public API entry points, and make internal visibility explicit with `pub(super)` where possible. Review for any leftover duplicated helpers or awkward cross-module imports.

Test requirements:

- run the full `ripdpi-runtime` unit suite
- run key `network_e2e` coverage
- if feasible for the branch, run ignored soak coverage as a final confidence pass

How it integrates with previous work:
This step is the cleanup and verification pass over all prior extractions; no new behavior should be introduced here.

Demo:
`runtime.rs` is now a thin entry layer, all focused modules are in place, and the full verification suite demonstrates unchanged behavior.
