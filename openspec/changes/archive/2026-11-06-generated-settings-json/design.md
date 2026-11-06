## Context

Eight grouped settings snapshots have duplicate per-field JSON writers and readers. BackupSettingsConverter is their only production consumer. Existing protobuf-to-snapshot mappers own domain normalization and remain in place.

## Goals / Non-Goals

- Goal: one model declaration for each exported field, with library decoding and explicit secret exclusions.
- Non-goal: change protobuf storage, native configuration, backup packaging, support settings packages, or UI.

## Decisions

- Add `@Serializable` to the root and section models. Use `@Transient` for PIN and LAN authentication token. Keep the existing serialized `Mode` enum.
- Pass a single `JsonTransformingSerializer` explicitly to encode/decode. It wraps the generated root serializer and changes only the flat/grouped shape. Derive section names from the root descriptor and refuse duplicate flattened keys.
- Keep the existing JSON instance and its defaults, null omission, and unknown-key policy. Numeric strings accepted by kotlinx.serialization remain accepted. Invalid numeric text, invalid structures, explicit null for non-nullable fields, and unknown enum values fail. Do not add another scalar parser.
- Remove the per-field JSON helpers. Retain protobuf mappings and existing validation. Pin the absent AWG wire-profile default to an empty string.
- Use the shared AWG preset inference when restoring fields. Validate the original wire profile and headers against the final inferred preset. Empty disabled settings stay off; incomplete custom settings fail before persistence.
- A flat DTO would lose useful grouping and approach JVM constructor limits. A grouped version 2 would change the backup contract without a product need.

## Contracts and ownership

- `:core:data:settings` owns the model and codec; `:core:data` owns backup restore and its atomicity regression test.
- Flat version 1 keys and the backup map key remain current. No legacy path is added.
- The primary writer owns the task slice. Review agents are read-only. No Rust, protobuf, dependency version, or golden file changes are planned.

## Risks / Trade-offs

- Credential exposure through generated fields: use `@Transient` and assert export exclusion and that injected secrets do not enter restored settings.
- Shape loss or name collision: assert representative fields in every section and reject collisions during flattening.
- Default changes: preserve declared values and test missing fields and the AWG profile requirement.
- More invalid input now fails: verify restore aborts before changing settings, groups, or rules.

## Migration Plan

No stored protobuf migration is needed. Canonical version 1 exports retain their shape. Malformed known values and non-canonical enum names that the manual parser accepted now fail. A revert restores the previous codec without changing stored bytes.

Validation: reproduce invalid-value acceptance first; run `build-gate -- ./gradlew :core:data:settings:testDebugUnitTest :core:data:testDebugUnitTest`, affected lint/static analysis, and `build-gate -- just ci-gates`. Obtain independent review, integrate the authorized change, and observe hosted CI before closure. No physical-device or live-service acceptance is claimed.
