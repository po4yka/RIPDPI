## Context

The proposal repairs three DPI-33 defects.

## Goals / Non-Goals

- Goal: explicit input rejection and regression tests.
- Non-goal: SRv6, raw privileges, new profiles or device acceptance.

## Decisions

Reject routing without inventing a routing model. Validate shared YAML/TOML inputs and guard direct factory bypasses. Match Kotlin UDP applicability. Preserve hopByHop2 as HBH plus fragmentable Destination Options and intentional prefragment placement. Raw on_fail and privacy acceptance remain gaps.

## Contracts and ownership

- IPv6 worker: strategy-ipv6, strategy-config and related TUN tests/docs in validation worktree.
- UDP worker: proxy-config/tests in separate worktree.
- Planning worker: task/OpenSpec docs and generated board. Parent: combined validation/integration.
- No lockfile, schema, locale, golden fixture, baseline, JNI or storage changes.

## Risks / Trade-offs

Invalid inputs previously accepted now return errors. Device behavior remains unverified.

## Migration Plan

No storage migration. Select a supported type instead of routing. Individual fix commits permit rollback. Run affected Cargo tests with --locked, strict planning checks, independent review, just ci-gates, architecture health and locked metadata before integration.
