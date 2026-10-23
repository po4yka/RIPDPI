## Context

The existing composite commit shares the recovery mutex with group edits and holds AWG then recovery locks. It journals immutable rows and groups, but a content-only CAS and refreshRevision miss group ABA. ProxyGroupCatalog is already the shared storage/flow owner.

## Goals / Non-Goals

- Goal: reject all captured group ownership predating replacement or effective mutation, and prove recoverable multi-peer commit boundaries with live collectors.
- Non-goal: change network protocol behavior, make independent stores atomically visible, or claim physical-device/network acceptance.

## Decisions

- Store an opaque UUID ownershipGeneration on ProxyGroup, with an empty unowned construction sentinel. Only persistence assigns authority. Remove the redundant accepted-refresh counter.
- Add always assigns a fresh generation. Ordinary edits rotate changed groups and preserve no-op transforms; restore and compensation rotate every replacement. Separate exact replaceAfterImage from user replacement.
- Under the existing recovery mutex, compare the complete stored group, prepare rows and metadata, allocate the accepted generation and serialize the intent before prepare. Replay reuses its full group collection and AWG after-images.
- Upgrade group storage to schema 2 and durably write migrated encrypted data before return. Legacy subscription intents receive a versioned, durable journal replacement before replay; failed upgrade retains recoverable state and never starts AWG writes. Android may update its process cache despite a failed synchronous commit, so group and journal stores share a per-preferences durability owner across instances. Before another read or mutation, an uncertain cache must be synchronously confirmed with a changing internal metadata value; repeated failure blocks access, while successful confirmation preserves the exact cached generation or intent. The shared owner also serializes complete journal read-check-write operations.
- Exercise a two-peer matrix with exceptions and cancellation at pre/post prepare, each credential and row, group persistence, before completion and after completion. Use the real coordinator and repository; only persistence ports are in memory. Check existing and cold collectors, readiness, repeated recovery, preparation counts and a subsequent competing edit.

## Contracts and ownership

- The task owner writes runtime-state storage/model, core:data recovery and test fixtures, affected app subscription consumers, and the task/specification. Reviewers are read-only.
- No serialized native/protobuf/locale/baseline lanes change. The new local generation stays out of wire delivery and exported fingerprints.
- Validation: affected runtime-state/core:data/app unit suites, staticAnalysis, architecture health, Cargo metadata, task-check, strict OpenSpec and required hosted CI.

## Risks / Trade-offs

- Every persisted edit changes equality; tests and callers must capture repository snapshots rather than constructor values.
- Random generation collision is bounded by UUID entropy; no unbounded tombstone store or counter reuse after reset is introduced.
- Old durable journals are commitments, so one-way migration retains their immutable credential material instead of discarding pending recovery.

## Migration Plan

Migrate encrypted group schema 1 to 2 exactly once and persist the result. Upgrade legacy subscription intent ownership metadata using journal.replace under the recovery owner before replay. Preserve already prepared generations and strict corruption handling. Older application versions do not own the new generation contract; rollback requires a forward corrective release, not a compatibility code path.
