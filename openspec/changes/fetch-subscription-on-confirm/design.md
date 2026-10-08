## Context

SubscriptionImportConfirmViewModel stores an ordinary subscription group, then emits success without fetching content. Bootstrap already waits for content. Manual recovery is fixed by the preceding commit.

## Goals / Non-Goals

- Goal: confirmed ordinary import uses the same validated and bounded persistence path as manual refresh.
- Non-goal: auto-connect, enable periodic refresh by default, delete failed groups, or replay consumed bootstrap delivery.

## Decisions

Inject SubscriptionRefreshCoordinator into the production constructor and pass its refresh method through the existing testable constructor pattern. After storing or reusing a group, await refresh for long-lived subscriptions. Only Updated emits success; Failed sets the existing error state. Preserve the existing CancellationException rethrow and importing-state cleanup. Keep failed groups for diagnostics and retries; existing members remain available.

A consumed bootstrap follows the existing local reconfirmation path without refresh, regardless of the new link flag. Updated(0) is valid for AWG-only content because the coordinator saves AWG profiles separately.

## Contracts and ownership

Only app ViewModel and tests change. Subscription import writer owns these paths; other writers own core parser and AWG policy. Parent integrates the generated board. No public JNI, protobuf, storage schema, locale, golden, or native changes.

## Risks / Trade-offs

A failed first import leaves a group with failure metadata so retry can reuse its identity. The screen keeps its current error and does not announce success. Existing network and payload limits bound the fetch. Cancellation can leave persisted partial work but never a success event; the stable refresh path makes retry safe.

## Migration Plan

No migration. Existing URLs reuse manual recovery. Verify SubscriptionInitialImportTest with real coordinator and MockWebServer, ImportConfirmViewModelTest bootstrap and ordinary cases, and refresh regressions. Run affected app ktlint and detekt. Revert the ViewModel commit to restore prior behavior.
