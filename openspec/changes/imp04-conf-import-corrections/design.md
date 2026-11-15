## Context

See proposal.md and the portfolio ownership record.

## Goals / Non-Goals

Fix the audited standalone import contract. Multi-peer activation, host option execution, standalone export UI, and native wire changes are out of scope.

## Decisions

- Preserve route policy from each input format. Apply URI defaults before the typed profile is rendered as conf.
- Validate the typed activation request before it can replace a saved row.
- Let the parser own lexical grammar and maintain a fixed list of reportable ignored option names.
- Retain the portable AWG subset. Represent empty optional v3 range and key values as unset.
- Keep committed work on separate branches until combined validation passes.

## Risks / Trade-offs

Raw conf without AllowedIPs no longer selects full routes. This prevents an undocumented route change. Multiple Interface blocks become a clear error.

## Migration Plan

No stored schema migration. Standalone runtime validation remains in place. Run owned JVM suites, source gates and independent review. Report device and server acceptance separately.
