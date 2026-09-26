## Context

`DefaultMainActivityHost` holds the pending archive request only in Activity-scoped fields. A new host receives the picker result after recreation but has no request to write.

## Goals / Non-Goals

- Goal: restore the pending export before the Activity result callback runs.
- Non-goal: persist an export across an explicit app restart or add new export formats.

## Decisions

- Save the pending request or source archive path in the Activity saved-state registry. Restore it when the new host registers. The result callback consumes the restored selection once.
- Keep the current archive writer and partial-document cleanup unchanged.

## Contracts and ownership

- Only `:app` Kotlin host state and tests change. No Rust crate, public API, protobuf, wire, or configuration contract changes.
- The saved-state Bundle is local to Android Activity restoration; no shared serialized file or migration is required.

## Risks / Trade-offs

- The source archive path may no longer exist after process death; the existing write-failure path reports this and attempts partial-document deletion.
- Saved state has a size limit; the request is small and stores only selection parameters.

## Migration Plan

No migration. A rollback returns to the previous Activity host behavior. Verify with a Robolectric recreation and picker-result test, `:app:testGithubFullDebugUnitTest`, `:app:detekt`, and app ktlint checks.
