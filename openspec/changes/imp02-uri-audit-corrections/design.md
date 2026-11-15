## Context

See proposal.md. The parser is local; accepting a link does not prove a peer handshake.

## Goals / Non-Goals

- Goal: Correct F01 through F05 with regression tests and one correction commit per finding.
- Non-goal: Add export UI, change native AWG packets, or run a physical/external peer probe.

## Decisions

- Catch only expected conf-conversion failures at the shared import boundary.
- Reject an explicitly empty URI route list without changing absent-route defaults.
- Omit empty optional I slots. Keep external empty I query rejection and document semantic normalization.
- Use fixed or allowlisted error labels rather than raw input prefixes.
- Preserve the current wire_profile marker and strict query allowlist.

## Contracts and ownership

App import worker owns ScannerUriDispatcher.kt and app import tests (F01/F04). Codec worker owns AmneziaWgUriCodec.kt and runtime-state tests (F02/F03). Documentation worker owns docs/amneziawg-uri-scheme.md and AwgProfileForm.kt KDoc (F05 and normalization text). The integrator owns all task/OpenSpec artifacts and the generated board. Writers use separate worktrees and do not edit another lane.

No Rust crate, JNI, protobuf, persistence schema, locale, dependency, golden, or baseline changes are needed. Changes stop at parser, serializer, and local error handling. The input-limit and path hypotheses remain outside these five confirmed findings.

## Risks / Trade-offs

Empty I slots normalize to absence; strict equality applies to canonical profiles. Explicit empty routes now fail rather than select defaults. Error text becomes less specific to protect credentials.

## Migration Plan

No stored schema migration. Existing canonical links retain their meaning. Roll back by reverting the individual correction commit. Run affected runtime-state/core-data/app unit tests, staticAnalysis, just ci-gates, architecture health, cargo metadata --locked, and a read-only pr-reviewer pass. Device and external transport proof are not required for these parser-only fixes; hosted CI is observed after push and recorded separately.
