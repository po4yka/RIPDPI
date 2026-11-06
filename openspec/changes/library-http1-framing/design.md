## Context

The affected crates use independent HTTP parsers. Android protection and TLS profile selection occur before HTTP and remain with the existing connector.

## Goals / Non-Goals

- Goal: delegate HTTP syntax and body framing to existing maintained libraries.
- Goal: preserve bounded resources, request fidelity, partial evidence, and task cancellation.
- Non-goal: change relay selection, DNS policy, JNI, persisted settings, or TLS fingerprints.

## Decisions

- Apps Script and protected DoH use Hyper HTTP/1 over the existing stream. Apps Script inbound HTTP and MITM connections use the same Hyper server path and typed responses.
- Diagnostic probes keep exact raw request writes. A bounded adapter gives library parsing access to responses without normalizing requests or blocking inside async polling. Keep measurement and strict framing policy separate from the codec.
- WebTunnel uses httparse for response heads. It retains exact request serialization and stops reading at the header boundary.
- Clients reject Transfer-Encoding plus Content-Length, conflicting lengths, unsupported transfer coding, and premature EOF. A typed-header policy check is not a second HTTP parser.
- Hyper owns ingress request framing. For Transfer-Encoding plus Content-Length it removes Content-Length and closes the connection after one request. Test that a pipelined suffix is not dispatched and neither raw framing header reaches the relay JSON. Do not add a second parser to override this library behavior.
- Keep application body and decoded gzip limits. Use bounded library metadata parsing and document any changed library-controlled metadata or timeout semantics in verification; do not rebuild a chunk parser to emulate incidental implementation details.
- Own connection drivers within the exchange lifetime. No detached task may outlive cancellation.

## Contracts and ownership

- Primary owns DNS, WebTunnel, Cargo.lock, workspace dependencies, task/spec files, and combined validation.
- Apps Script and diagnostics writers use separate worktrees and own only their assigned crates and approved direct consumers.
- Dependencies use locked versions: hyper 1.12.0, hyper-util 0.1.21, http 1.5.0, http-body-util 0.1.5, bytes 1.12.1, tokio 1.54.1, httparse 1.10.1.
- No Kotlin, JNI, protobuf, configuration, or storage migration is intended.

## Risks / Trade-offs

- Library serializers can alter diagnostic probes: retain raw request ownership.
- Read-ahead can lose tunnel or pipelined bytes: test boundaries and keep library-owned connection state.
- Library permissiveness can weaken existing framing checks: validate typed headers before body consumption.
- Runtime and cancellation regressions: use existing runtimes and independent async review.

## Migration Plan

Add failing behavior tests, replace codecs and all affected callers, then run affected Rust tests and clippy through build-gate. Run just ci-gates and architecture checks on the combined tree. Obtain independent code and async review before commit. Rebase onto origin/main, fast-forward main, push, and inspect hosted results. Reverting the change restores the previous implementation without data migration. Device acceptance and APK size are not claimed by host tests.
