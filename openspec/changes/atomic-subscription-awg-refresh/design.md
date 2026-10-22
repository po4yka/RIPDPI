## Context

Group mutations and AWG after-images share ProfileMutationRecoveryCoordinator's mutex. Calling AWG persistence inside updateGroup would recursively acquire that mutex. The current HTTP parser instead writes too early.

## Decisions

- Persist a monotonically increasing `Subscription.refreshRevision`, allocated by the commit owner, so equal wall-clock timestamps cannot hide a newer accepted AWG-only generation. Reject exhausted or negative revisions before preparation; never invent timestamps.
- Keep response parsing pure and stage typed AWG subscription inputs in the outcome.
- AwgProfileRepository holds its existing mutation mutex, then invokes the recovery owner's typed subscription commit. The owner recovers and compares the persisted exact group before invoking the pure after-image preparation callback.
- The callback reads DAO/credential state and builds immutable rows without recursively calling recovery or a repository mutation. Lock order remains AWG mutex then recovery mutex; no inverse acquisition is introduced.
- One encrypted SubscriptionRefreshIntent contains AWG after-images and the complete current group collection with the accepted replacement. Replay writes AWG rows/secrets then the group blob; the existing journal owner publishes the catalogue and completes the record.
- Extract the existing blob, mutex and observer state into one runtime-state ProxyGroupCatalog shared by the repository and mutation stores. Replay uses its replacement-and-publication operation, so direct recovery repairs observers even when the original caller was cancelled. Observation remains read-free; nonpublishing snapshots do not bypass startup loading. Lock order is AWG mutex, recovery mutex, then catalogue mutex.
- Before replaying any subscription AWG write, validate the existing group blob even on cold recovery. An unreadable catalogue preserves its bytes and pending journal and publishes no fallback; repair is required. A valid journal does not authorize overwriting corrupt private state in this change.
- A cancellation or store failure retains the journal for roll-forward before later mutations. This is recoverable ownership, not a claim that independent store readers observe an atomic database transaction.

## Scope and contracts

Single writer owns app subscription orchestration, core-data AWG/mutation owner, runtime-state journal family and regression tests. No alternate locks, schema baseline changes, new dependencies or direct credentials. Existing selector-reserve history remains intact; its explicit AWG exclusion is superseded only by this new capability.

## Validation

First reproduce delayed valid AWG rotations after source/group changes with the production parser and AWG repository. Then verify accepted rotation, stale-before-prepare behavior, mutation serialization, failure/cancellation replay, cache publication and existing AWG tests. Run affected module tests and static analysis through build-gate, task/OpenSpec validation and architecture-health; require full hosted CI before closure.

## Migration and risks

The internal encrypted journal adds a subscription intent/family. The group subscription gains an additive accepted-refresh revision (absent values decode as zero); AWG row format is unchanged. A pending new intent must be recovered by the matching client before rollback. Unreadable group bytes block automatic recovery and require repair. Checked atomic write failures and cancellation with readable prior bytes remain recoverable. Cancellation can leave partially written independent stores until recovery; public mutation paths must recover first, and no success is reported early.
