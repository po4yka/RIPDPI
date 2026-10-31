## Context

`DohJsonSurveyRunner` is registered in monitor-engine but emits a placeholder. The async probe crate has endpoint metadata and a JSON parser, but no production HTTP client. Monitor-engine uses synchronous stage runners and reaches diagnostics HTTP through `ripdpi-monitor-lane-adapter`.

## Goals / Non-Goals

- Goal: emit measured, per-resolver JSON DoH results for an explicitly selected DNS target.
- Non-goal: add a runtime DNS resolver, silently survey unrelated domains, or change the JNI wire shape.

## Decisions

- Use the existing synchronous diagnostics HTTPS client through the lane adapter. It already routes via the scan transport, verifies TLS, bounds response size, and inherits the scan I/O deadline. No async runtime or new dependency is needed.
- Add a fixed JSON `Accept` variant to the HTTP helper. Cloudflare requires `application/dns-json`; other HTTP probe requests keep their current header.
- Keep the documented unauthenticated Google, Cloudflare, and AdGuard resolver endpoints in one shared Rust table. Alibaba's documented JSON API requires account credentials, so remove that endpoint from the default panel. The async probe's default panel and the monitor stage read the same table.
- Encode the request target as an HTTP query value and reject an empty or oversized DNS name before I/O. A successful HTTP status counts only after the shared JSON parser finds an IP address. Preserve individual resolver status and latency in details.
- Run the panel sequentially. Check cancellation and the ambient scan deadline between endpoints; report an incomplete result if the survey stops early. Keep the stage in the existing no-task default plan.

## Contracts and ownership

- Kotlin diagnostics planning selects one survey task per DNS domain; local-network admission removes that task when its DNS target is denied. The Rust monitor stage also deduplicates domains expanded across resolver candidates.
- Rust crates: `ripdpi-diagnostics-contracts` owns endpoint metadata; `ripdpi-diagnostics-http` owns the JSON request and in-flight abort watcher; `ripdpi-diagnostics-probes` consumes the shared panel; `ripdpi-monitor-engine` executes and records the stage.
- This worktree is the only writer for these files and the task/OpenSpec artifacts. JNI, protobuf, storage, and locale schemas do not change.

## Risks / Trade-offs

- Public resolver APIs vary; one provider's HTTP error is recorded as its own status and never hidden by another provider's success. An unavailable panel is inconclusive rather than proof of censorship.
- Three sequential network calls per distinct domain can consume stage time. The DoH request watcher closes the active socket on cancellation or deadline; subsequent calls stop.
- The survey sends each requested DNS domain to third-party resolvers. It uses only the scan's DNS targets.

## Migration Plan

No persisted or wire migration is needed. Add focused fake-client tests for requests, response mapping, cancellation, and default scheduling. Run affected `cargo test --locked`, architecture checks, OpenSpec/task validation, and contract tests. Revert the feature commit to roll back behavior; existing scan requests remain compatible.
