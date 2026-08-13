## Context

The VLESS Reality relay currently emits generic native runtime events into a
bounded process-wide relay ring. The Android adapter drains that ring without
runtime filtering, the Kotlin event model has no typed attempt-stage fields,
and `RuntimeArtifactPersister` persists only proxy and tunnel native events.
Consequently a relay reset survives mainly as a final free-form message and
cannot be localized to one connection attempt.

The relevant protocol path is split across `ripdpi-vless`,
`ripdpi-relay-core`, and `ripdpi-relay-android`. A direct connection reaches TCP
connect, Reality TLS, VLESS request write, lazy first-response validation,
SOCKS reply, and stream termination. Multiplexed sessions can reuse an existing
carrier, so a logical attempt must record carrier reuse rather than fabricate
TCP or Reality stages. First-response validation happens during the first
upstream read and therefore needs a one-shot state transition, not per-read
telemetry.

Runtime telemetry schema version 3 is additive and forward tolerant. The
diagnostics database is version 10 and the archive schema is version 14 before
this change. New persisted columns require an explicit Room 10-to-11 migration;
the new archive entry requires schema version 15 and updated fixtures.

## Goals / Non-Goals

- Goal: retain a bounded, ordered, structured trace of the protocol stages
  actually observed during one VLESS Reality TCP relay attempt.
- Goal: correlate a trace with its native runtime and Kotlin-owned connection
  session after terminal persistence and archive export.
- Goal: preserve partial evidence and typed failure details without inferring a
  cause that the runtime did not observe.
- Goal: keep older native producers and Kotlin consumers compatible through
  optional, defaulted fields.
- Non-goal: packet capture, payload inspection, traffic recording, endpoint or
  credential export, or per-packet/per-byte telemetry.
- Non-goal: changing relay selection, retry, timeout, or VPN lifecycle policy.
- Non-goal: declaring censorship, server misconfiguration, or an actor from a
  stage transition alone.

## Decisions

- Each relay runtime allocates a monotonically increasing runtime-local numeric
  attempt identifier. It is opaque outside correlation, contains no UUID,
  endpoint, credential, device, or network identity, and is combined with the
  existing runtime identifier to avoid cross-runtime ambiguity.
- The canonical stage vocabulary is `tcp_connect`, `reality_tls`,
  `vless_request`, `vless_response`, `socks_reply`, and `relay_stream`.
  Outcomes are `started`, `succeeded`, `failed`, `cancelled`, and `closed`.
  Carrier reuse is represented explicitly as `carrier_reused`; stages not
  executed on a reused carrier are absent.
- Every record carries an attempt-local monotonic sequence number. Emission
  sites report only observed transitions. Optional fields are `durationMs`,
  `failureStage`, `failureClass`, `ioErrorKind`, `osErrorCode`,
  `peerClosePhase`, and `carrierDisposition`; unavailable values remain null.
- The SOCKS session future is instrumented with an attempt span. The bounded
  ring layer inherits only an allowlist of span fields (`ring`, `source`,
  `subsystem`, `runtimeId`, `attemptId`) and maps explicit event fields into the
  typed record. Arbitrary span metadata is not copied.
- VLESS first-response validation emits exactly one success or failure
  transition from a guarded wrapper state. Subsequent reads emit no stage
  events. This keeps telemetry out of the steady-state byte path.
- Relay event storage is partitioned or drained by runtime identifier. Draining
  one runtime must retain events belonging to other active runtimes. Capacity
  remains bounded with drop-oldest behavior, and the snapshot exposes dropped
  relay-event count so archive completeness can distinguish absence from
  eviction.
- `NativeRuntimeEvent` and the Rust Android telemetry adapter gain nullable
  typed fields. The runtime telemetry schema stays at version 3 because the
  wire change is additive and all new Kotlin fields have defaults.
- `RuntimeArtifactPersister` includes relay native events in live and terminal
  persistence. Kotlin assigns the owning `connectionSessionId` at persistence
  time; native code does not need to know the Room session key.
- Archive runtime configuration exposes `effectiveConfigFingerprint`, computed
  as a versioned full SHA-256 digest of a canonical privacy projection derived
  from `BypassStrategySignature`. Free-form DNS labels, fake SNI values, and QUIC
  fake hosts are removed before hashing; raw native configuration, relay
  credentials, endpoints, network fingerprints, and the connection-policy
  signature are never hash inputs. The signature object stays absent from
  production archives so custom strategy values cannot escape as adjacent
  plaintext evidence.
- `NativeSessionEventEntity` stores typed fields rather than requiring message
  parsing. Room advances from version 10 to 11 with nullable columns and an
  explicit migration. Existing rows remain valid with null attempt fields.
- Archive schema 15 adds `relay-attempt-traces.jsonl`, ordered by connection
  session, runtime, attempt, and sequence. Redaction applies before rendering,
  and completeness reports retained and dropped trace-event counts. The
  existing native-event CSV remains available for general runtime events.

## Contracts and ownership

- `native/rust/crates/android-support`: optional event fields, allowlisted span
  inheritance, bounded retention, runtime-scoped drain, and drop accounting.
- `native/rust/crates/ripdpi-vless`: TCP, Reality TLS, VLESS request, and
  one-shot response-validation transitions.
- `native/rust/crates/ripdpi-relay-core`: SOCKS result and relay stream terminal
  transitions, including cancellation and peer close where observed.
- `native/rust/crates/ripdpi-relay-android`: runtime-local attempt allocation,
  session-span correlation, and runtime-scoped event snapshot export.
- `native/rust/crates/ripdpi-android-telemetry-adapter`: additive serialized
  event-field projection with defaults.
- `core/engine`: Kotlin `NativeRuntimeEvent` contract and compatibility tests.
- `core/service`: relay snapshot collection and live/terminal persistence.
- `core/diagnostics-data`: Room version 11 entity and 10-to-11 migration.
- `core/diagnostics`: redaction, ordered JSONL projection, schema 15
  completeness metadata, and archive fixture coverage.
- Serialized single-writer files include the native event JSON shape, Room
  entity/database/migration declarations, archive schema constants, and
  telemetry/archive golden fixtures.

## Risks / Trade-offs

- Bounded-ring eviction can remove an early stage. Drop accounting and archive
  completeness expose this as partial evidence instead of presenting a complete
  trace.
- A process-global destructive drain can make concurrent runtimes steal each
  other's evidence. Runtime-scoped partitioning or retain-on-filter semantics
  is required and covered by a concurrent-runtime regression test.
- Span inheritance can leak unrelated metadata. The collector copies only the
  fixed allowlist and privacy tests reject credentials, endpoints, UUIDs,
  handshake bytes, and payload-like fields.
- First-response validation is on a read path. A one-shot guarded transition
  bounds its cost; focused tests and review verify that steady-state reads do
  not emit or allocate telemetry records.
- Mux carrier reuse has a different stage graph. The explicit
  `carrier_reused` disposition prevents false TCP/Reality milestones.
- Room rollback to an older APK may recreate the diagnostics database because
  the repository does not promise downgrade migrations. User settings are in a
  separate store and are unaffected.
- Stage evidence localizes the observed failure boundary but does not identify
  the remote actor or root cause. Findings and summaries retain qualified
  language.

## Migration Plan

1. First persist the relay events already present in runtime snapshots through
   live and terminal/outbox paths and expose them through the existing redacted
   `native-events.csv`. This slice changes no native, Room, or archive schema.
2. Export the effective allowlisted strategy fingerprint through the existing
   `runtime-config.json` payload and teach offline analytics to consume it while
   retaining fallback support for older archives. This additive field requires
   no native, Room, or archive schema-version change.
3. Add optional native event fields, allowlisted span inheritance,
   runtime-scoped relay draining, and drop accounting using focused RED/GREEN
   Rust tests.
4. Emit the direct and multiplexed VLESS/SOCKS stage transitions with one
   attempt-local sequence and focused success, reset, cancellation, reuse, and
   one-shot response tests.
5. Mirror optional fields in Kotlin, prove older/missing-field decoding, add the
   Room 10-to-11 migration, and persist relay events in live and terminal paths.
6. Add redaction and ordered `relay-attempt-traces.jsonl` rendering, advance the
   archive schema to 15, and update completeness and archive fixtures. Golden
   fixture regeneration requires explicit user authorization for the affected
   telemetry/archive fixture family.
7. Run focused Rust tests with `--locked`, Kotlin contract/migration/service/
   archive tests, privacy checks, `cargo fmt`, affected Clippy gates,
   `./gradlew staticAnalysis`, architecture health, task/OpenSpec validation,
   and owner-style review. Hosted CI, physical-device proof, artifact
   verification, and deployment remain separate evidence.

Rollback is an application-code rollback plus archive-schema reversion before
release. After a version-11 diagnostics database has been opened, an older APK
may recreate only diagnostic history; it must not silently reinterpret typed
trace columns or affect user settings. No dual-write compatibility path is
introduced.
