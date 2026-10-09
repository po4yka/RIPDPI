## Context

Task DGN-1791521873290293 extends the active synchronous throughput path in diagnostics-runner and monitor-engine. The existing ru-throttling profile supplies the targets. Dormant unprotected async throughput code is not used.

## Goals / Non-Goals

- Goal: live and durable transfer evidence with bounded memory, time and cancellation.
- Non-goal: infer provider intent, introduce a backend, change target catalogs or implement repeated independent size experiments.

## Decisions

- Extend the active protected direct/SOCKS HTTP/1.1 path. Count payload bytes with strict Content-Length/chunked/EOF framing. Keep responseComplete separate from windowComplete and preserve existing throughput outcome policy.
- Use monotonic elapsed times from attempt start, null first/last body times before any data, at most 64 samples per run and at most 10 runs. Emit live progress at most four times per second plus initial/final state. Refresh socket timeout against cancellation and deadlines; short polling timeouts alone are not stalls.
- Add optional transferProgress={target,measurement} to existing progress wire/domain; omit when absent. Measurement fields: runIndex,runCount,receivedBodyByteCount,expectedBodyByteCount?,elapsedMs,firstBodyByteMs?,lastBodyProgressMs?,terminationReason?,responseComplete,windowComplete,samples[{elapsedMs,bodyByteCount}].
- Store detail transferEvidence as bounded JSON {version:1,runs:[measurement]}. No target identifiers or payloads inside numeric evidence. Existing result target/protocol details supply context.
- Stable termination reasons: content_length_complete,chunked_complete,eof_complete,early_eof,window_limit,idle_timeout,reset,read_error,invalid_framing,cancelled,deadline,setup_error,http_error. Network-scope warnings remain context and do not rewrite the observed native stop cause.
- Kotlin validates nonnegative monotonic numbers, strict run/sample bounds and known shape. Numeric evidence survives export redaction; raw bodies are never retained. History uses the same renderer as current results. Localized labels land in all ten locales.

## Contracts and ownership

Native writer owns Rust contracts and live callback plumbing. Integration writer owns Kotlin wire/model/codecs and redacted export. UI writer owns app and locales. No JNI method, database, protobuf, catalog, dependency or schema-version changes. Null omission preserves old serialized payloads. Shared fixture changes require applicable approval.

## Risks / Trade-offs

- Malformed framing or server errors can resemble network restrictions: show observations only.
- Read cancellation must not destroy partial counters or leave stale live state: test stage transitions and repeated runs.
- Bounded sampling loses fine-grained events: retain first/final samples and report observed cumulative bytes, not a claimed filter threshold.

## Migration Plan

Old reports decode with absent evidence. New optional wire fields are produced and consumed by the bundled versions. Rollback ignores generic details. Validate strict OpenSpec, Rust framing/cancel/timing tests, Kotlin roundtrips/redaction, Compose rendering, staticAnalysis, locale lint, locked Cargo metadata and architecture contracts. Device and remote CI are separate evidence.
