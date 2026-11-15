## Context

The Connectivity stage runs one socket sequence per target, followed by bounded alternatives. It reports one application byte counter and a response count. The runner used planned bytes and a timeout or EOF to attribute a threshold.

## Decisions

- Keep the HEAD series and the request and report envelopes. Count accepted application bytes, completed requests, and final responses. Label successful short runs as partial coverage. Record failure phase and administrative stop separately. Keep an observed late failure as evidence with its byte count. Report a threshold as inconclusive when the experiment lacks a matched control that isolates and locates the threshold.
- Use structured transport-open stages to choose eligible retry. Route the existing stage cancel flag through connect, handshake, and the socket watchdog. Attempt budgets include alternate names and alternate ports. Apply cancellation before the first attempt and every new attempt.
- Keep SNI and Host separate. Compare candidates with one fixed Host and respect endpoint routing. Report the baseline and measured results for each attempt. Do not convert rejected or incomplete responses into a failed network verdict.
- Own HTTP semantics in the diagnostics HTTP and contract helpers. Own target and resource validation in catalog generation and monitor admission. Own report projection in classification, the engine summary, and Kotlin display.

## Validation

Use independent loopback peers for plain and TLS, injected local socket failure, FIN and RST, timeout, cancellation, short coverage, and alternate attempts. Run focused Rust, catalog, and Kotlin regression tests, then the source and contract gates. Read-only review each implementation lane. Changes to expected golden output require the named fixture authorization. Device and external provider tests are outside this patch's acceptance.
