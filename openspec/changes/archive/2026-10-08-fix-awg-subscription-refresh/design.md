## Context

AWG refresh calls save without existingId. The saved ID is a random UUID used in runtime telemetry. Room stores a tolerant activation-request JSON blob; secrets use a separate credential store and mutations use the existing recovery coordinator.

## Goals / Non-Goals

- Goal: durable scoped identity, local-key preservation, and idempotent refresh.
- Non-goal: DNS/routes, token expiry, protocol changes, and automatic legacy ownership inference.

## Decisions

- Add a private subscriptionOrigin object to the stored JSON, separate from activation requests. It contains the group ID and member key, not the URL or bearer token.
- Use explicit sing-box tag and INI peer public key as scoped member keys. Reject duplicates before writes. Endpoint and cohort parameters can rotate without changing identity.
- Reuse opaque IDs and preserve provenance on ordinary editor saves. Hold the repository mutex across lookup and mutation. Use existing durable mutation recovery for row and secret writes.
- Preserve the existing private key only when the incoming key is empty. Server PSK updates remain authoritative. Preserve existing DNS/routes and carrier fields that the current subscription mapper does not import, so refresh does not undo local corrections.
- Associate bootstrap imports with their parsed group ID. The bundled seeder creates a BASIC group without a subscription and stays unchanged. Never adopt a manual or legacy profile by label.

## Contracts and ownership

- Own :core:data AWG repository/blob metadata, :app refresh/bootstrap and tests. No Rust, JNI, protobuf, Room column, locale, golden, dependency, or baseline changes.
- Metadata is additive and ignored by old tolerant readers. It never enters native runtime telemetry. Existing backup deny-all covers Room storage.
- Verification: AwgProfileRepositoryRoomTest, SubscriptionAwgRefreshTest, SubscriptionRefreshCoordinatorTest, ImportConfirmViewModelTest, Kotlin lint and static analysis, architecture-health and task validation.

## Risks / Trade-offs

- Legacy rows lack provenance: leave them intact. The first refreshed template needs its local key set once; later refreshes update it safely.
- Tag or INI public-key change creates a new identity. No safe automatic association exists without a server-issued stable ID.
- Each row uses existing journaled mutations; a multi-row batch is not atomic. Retry is idempotent for rows already saved.

## Migration Plan

No Room migration is needed. New imports write metadata; old blobs decode unchanged. Downgrade can read updated blobs but may drop metadata when editing. Restore the new version and import again to re-establish ownership.
