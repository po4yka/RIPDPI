## Context

Task `TRN-1792673614153980` complements
`po4yka/ripdpi-vpn-deploy#OPS-1792673523985723`. The existing sing-box parser
understands additive RIPDPI schema 1 fields. Subscription refresh atomically
transforms relay members and subscription metadata through `updateGroup`.
AmneziaWG profiles use a separate recoverable row/credential store, with stable
subscription-origin identities and idempotent batch writes. Real native candidate
relay probes already require a complete bounded HTTP body and owned cleanup.

## Goals / Non-Goals

- Goal: distinguish receipt from an explicit successful test of a declared new
  Reality/Vision, XHTTP, Hysteria2 or AmneziaWG path and export offline evidence.
- Non-goal: automatic telemetry, mandatory backend, persistent relay selection,
  hardware attestation or proof that every application or transport is healthy.

## Decisions

- Add optional `ripdpi.migration` with exactly `schema_version: 1`, lowercase UUID
  `migration_id` and `delivery_id`, distinct `target_tags`, and a 64-lowercase-hex
  `receipt_key`, and required `awg_previous_tags` map (empty when no AWG). Map
  keys identify declared AWG targets; previous tags are unique bounded ASCII tags. Tags have 1..128 ASCII characters matching `[A-Za-z0-9_.:-]+`;
  the list has 1..128 entries. Strict validation rejects missing/extra keys,
  ambiguous tags, unsupported versions and targets absent from imported profiles.
- Hash the exact bounded received HTTP entity bytes before text decoding. Do not
  hash parsed or reserialized JSON. Migration payloads use strict UTF-8. The
  outer RIPDPI schema remains 1, with no embedded self-referential payload digest.
- Store metadata and receipt state inside the existing sealed subscription blob.
  Receipt export is a whitelist, never serialization of the subscription object.
  Persist received state only after relay parsing and every AWG save succeed,
  and atomically with the final group update. A crash before that commit cannot
  acknowledge delivery. Capture target identities from the actual persisted rows;
  stale partial writes cannot qualify as tested evidence. A changed digest or
  delivery resets tested state; absent metadata removes old receipt authority.
- Use a dedicated migration verification coordinator, not selector URL-test's
  latency signal or generic connected status. Select only an exact declared target.
  Map relay targets with `mapRelayProfile`, then call `CandidateRelayMeasurements`.
  Reality/Vision, XHTTP and Hysteria2 use this existing native implementation.
- For AWG, the user first connects the imported profile. A service-owned active
  probe registry captures the exact resolved config consumed by
  `AmneziaWgRuntimeSupervisor`, a unique session token and runtime-alive predicate
  after readiness. Revoke ownership before stop/detach and unexpected termination.
  Explicit measurement resolves the expected imported request, compares full
  consumed configuration, probes the dedicated AWG SOCKS port, and rejects any
  changed ownership, configuration or physical network before/after the request.
  Do not start another tunnel or treat an equal profile ID as config equality.
  The supervisor publishes to a small runtime registry; HTTP measurement depends
  on that registry, keeping lifecycle ownership independent of diagnostics.
- AWG migration maps a new opaque tag to its previous published tag only in the
  same subscription group. Copy the existing local key and editor-owned overrides
  into the new row while replacing delivered fields and preserving the old row.
  Never search global profiles or another group for keys.
- AWG private-key placeholders may be completed with the device's local private
  key. Bind the declared server-owned fields to the imported target, then capture
  the full rehydrated request for active-config comparison. Delivered field edits
  invalidate evidence; local key completion is distinct from changing the server.
- Snapshot delivery, target configuration and network epoch before measurement.
  After success and cleanup, atomically compare the same delivery and target before
  recording tested state. The final AWG commit and tested export hold the existing
  AWG row mutex while checking the group snapshot, in AWG-row then group order;
  no network work or AWG repository re-entry occurs under that guard. A monotonic
  catalog mutation generation is compared inside the final group transaction to
  reject backup/recovery mutations that bypass the row mutex. Tested export reads
  both stores under the shared journal read guard.
  Cancellation and any mismatch produce no success receipt.
- Expose a user-editable HTTP(S) probe URL, prefilled from the subscription's
  urltest policy when available. Do not add a mandatory external probe service.
  The URL is never included in the receipt. Existing HTTP probes use explicit
  SOCKS, no redirects, no connection retry and bounded complete-body verification.

## Contracts and ownership

- The exported JSON has exactly `schema_version`, `migration_id`, `delivery_id`,
  `payload_sha256`, `state`, `received_at_ms`, `tested_at_ms`, `tested_tag`, `mac`.
  Version is 1. State is `received` or `path-tested`. Received time is a positive
  integer; untested time is 0 and tag is empty; tested time is at least received
  time and tag is one declared target. Digests and MACs are lowercase hex SHA-256.
- MAC is HMAC-SHA256 keyed by decoded `receipt_key` bytes over UTF-8:
  `ripdpi-device-migration-receipt-v1\n`, migration ID, newline, delivery ID,
  newline, payload digest, newline, state, newline, decimal received timestamp,
  newline, decimal tested timestamp, newline, tested tag, final newline.
- Deployment owns the canonical receipt schema and golden vectors; copy identical
  reviewed fixtures through the existing `core/data/src/test/resources/contract`
  mechanism. No protobuf, JNI, Rust runtime or generated native schema change.
- Runtime-state owns parser/models/receipt encoding and bounded payload reads.
  Core data owns stable AWG origin lookup. Service owns exact-runtime proof.
  App owns refresh/bootstrap, coordinator, subscription status UI and FileProvider
  export. One client writer owns all locale resources and shared fixture edits.

## Risks / Trade-offs

- Shared-secret MAC proves delivery-key possession and binds receipt fields; it
  does not attest an uncompromised device or prevent the owner from fabricating a
  receipt. Operator retirement policy must retain this trust boundary.
- AWG and group persistence span stores. Commit receipt authority last, keep batch
  retry idempotent, and require exact current target comparison for test/export.
- Server tags are technical identifiers. Never derive exported tags from device
  names or endpoints; reject ambiguous parsing and use only declared tags.
- No test fixture substitutes for a real device or live server result. Local and
  hosted checks establish software behavior; live four-transport acceptance must
  be separately observed and explicitly reported when absent.

## Migration Plan

Add optional local state with inert defaults, retain ordinary non-migration
subscriptions, and fail closed for malformed migration metadata. A metadata-free
refresh removes migration authority. The rollout uses matched server/client
contract fixtures before enabling migration deliveries. Reverting the feature
leaves normal subscriptions usable but removes receipt-based retirement evidence.

Run affected data/runtime-state/service/app unit suites, `staticAnalysis`,
`:app:lintGithubFullDebug`, `:core:service:lintDebug`, architecture-health and
locked Cargo metadata checks, plus `just task-check`. Use the machine build gate
for Gradle. Render and exercise the status actions with focused UI/device checks;
report unavailable device or live topology evidence separately.
