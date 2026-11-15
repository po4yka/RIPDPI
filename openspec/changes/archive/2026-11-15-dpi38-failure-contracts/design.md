## Context

Task ENG-1794722736729815 corrects audit F1-F9 on the existing synchronous Rust socket runtime. Tokio 1.54.1 is a pinned dependency, but no new asynchronous runtime is needed. Rustls 0.23.46 and libc 0.2.191 are already pinned. The rootless, offline-first contract remains in force.

## Goals / Non-Goals

- Goal: preserve evidence and attempt ownership across flow concurrency, retries, incomplete responses and cancellation.
- Goal: one fix commit for each finding, with observed regression evidence.
- Non-goal: a new general QUIC close decoder, HTTP/2 or HTTP/3 decryption, DNS pool redesign, external probes or physical/root acceptance.

## Decisions

- The integration owner defines the adaptive port contract: add an opaque Copy evolution attempt token with a runtime generation and unique attempt identity. Hint methods that include evolution return both AdaptivePlannerHints and an optional token. Feedback success/failure takes the exact token, and a neutral discard operation removes it without reward. Other adaptive hint methods retain their current contracts. Do not create a new trait hierarchy.
- The evolution implementation owns context, combo and start time for each live token in bounded pending state. Two flows must retain separate attempts even in one context. Feedback is at most once. A reset invalidates old generations. Expiry runs on the runtime lookup path. Store the token with the emitted TCP/UDP attempt, and do not reward skipped or uncommitted execution. Reuse existing attempt/receipt ownership patterns.
- Enforce a connection-wide count of route advances. Carry the count through existing route or connection state so reconnect cannot reset it. A same-group no-TFO retry remains an independently bounded compatibility fallback, not an extra group advance. Payload-only route selection does not spend this failure retry budget. A provisional SOCKS connection retains its consumed count when payload policy selects the final route. Never replay uncertain committed payload.
- Use bounded response observation with incomplete, neutral, classified-failure and accepted-evidence outcomes. Share boundary decisions between first exchange and rotation. Preserve bytes and use explicit deadlines and byte limits. HTTP headers/body prefix and TLS records must survive arbitrary read boundaries. Do not train success after a limit-truncated observation.
- Validate a complete TLS alert envelope before reading its fields. Keep closure neutral. TLS 1.2 nonfatal warnings may continue; TLS 1.3 protocol errors must remain failures. Do not infer version solely from the record legacy version.
- Keep network reprobe a baseline connectivity observation unless it actually executes the selected strategy. Use an existing valid modern ClientHello builder and classify origin incompatibility separately from transport failure. Normalize WouldBlock only at the known blocking timeout boundary.

## Contracts and ownership

HTTP observation covers the first response message. Unsupported transfer or content encoding, an informational response, or an unconfirmed request method gives a header-only neutral result unless the headers provide a failure signal. A partial method sent after the outbound assembly window cannot establish HEAD or CONNECT body framing. The proxy still forwards all bytes. Full transaction parsing and decompression are outside this change.

TLS observation uses at most 16 KiB, or a lower caller limit. The absolute deadline starts before the first response read. The configured deadline takes precedence; a 250 ms fallback bounds an otherwise unbounded observation. New chunks do not extend this deadline. Exhausted observation is neutral; transport errors and cancellation keep their existing error paths.

A complete malformed response after a recognized complete TLS request keeps the existing generic handshake-failure policy. Invalid bytes must not supply a specific peer alert code. Malformed evidence after an unconfirmed partial TLS request is neutral. The inspection window bounds content evidence. A genuine EOF, reset or I/O timeout remains independent transport evidence for a rotation round that still owns an observation, even after that content window. A round already closed as neutral cannot receive feedback from a later event.

The pending first-response reader observes the shared first-response owner. Neutral means that this ownership has ended; F10 publishes that state before a local cancellation closes upstream. The reader must not use the general outbound `peer_done` flag as cancellation evidence, since ordinary client FIN also sets it. A normal write-half close can still receive a complete close-delimited response.

TLS 1.2 permits alert fragmentation and coalescing under [RFC 5246 section 6.2.1](https://www.rfc-editor.org/rfc/rfc5246.html#section-6.2.1). TLS 1.3 forbids both under [RFC 8446 section 5.1](https://www.rfc-editor.org/rfc/rfc8446.html#section-5.1). Process messages in order: an earlier fatal alert remains a failure, while close_notify ends observation and later data is ignored under [RFC 5246 section 7.2.1](https://www.rfc-editor.org/rfc/rfc5246.html#section-7.2.1). Pinned rustls rejects some valid TLS 1.2 record layouts, so these cases must keep specification evidence separate from positive interoperability evidence.

- No Kotlin, JNI, protobuf, storage, native JSON or dependency schema changes are planned. Internal Rust adaptive ports and route/outcome models may change; update all callers and generated API snapshots through their owning exporter.
- The portfolio record owns the parallel path lanes. Writers use isolated worktrees and separate commits. Integration resolves shared session fields and re-exports after reading each diff.
- The integration owner alone owns Cargo.lock, manifests, API snapshots, planning files, verification and combined gates. No baseline changes or golden blessing is authorized by this change.
- Use the repository task wrapper for execution state. Do not change generated architecture reports by hand.

## Risks / Trade-offs

- More response buffering can increase latency: cap bytes and time, and leave explicit inconclusive outcomes.
- Token propagation can omit a caller: compile the affected dependency graph, test TCP and UDP, and require an independent review.
- A clean origin can send an ordinary TLS alert: do not claim DPI from a single baseline rejection.
- Parallel changes can conflict: integrate serially in the dedicated job branch and recheck after rebasing on origin/main.
- Gates can expose unrelated shared-checkout work: preserve it and report the owning failure rather than weakening enforcement.

## Migration Plan

No stored-data migration is needed. Land ten reviewed fix commits, run affected crate tests, formatting, Clippy, workspace check, cargo metadata --locked and just ci-gates. Validate task/OpenSpec records. Rebase on origin/main and rerun affected gates if inputs changed. Fast-forward main and push as authorized. Rollback uses ordinary revert commits; no history rewrite is needed. Device and external-network acceptance remain explicitly unclaimed.

The scoped TLS alert rules were also checked against [RFC 9846 sections 5.1 and 6](https://datatracker.ietf.org/doc/html/rfc9846#section-5.1), which replaces RFC 8446. These sections retain the record framing and closure/error rules used here. This source repair does not claim full TLS conformance.
