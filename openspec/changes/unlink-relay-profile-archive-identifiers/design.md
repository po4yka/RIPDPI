## Context

`relayProfileToken` is SHA-256 of the saved profile ID. The current archive copies it into native event JSON and relay health JSONL. Startup attempt IDs include its prefix, and relay health event IDs derive from both values.

## Goals / Non-Goals

- Goal: Preserve relay health grouping inside one archive without exposing identifiers that join separate archives.
- Non-goal: Change the on-device event database, the service recorder, or unrelated archive correlations.

## Decisions

- Redact the profile token, attempt ID, and derived event ID when projecting relay health native events into archive JSON. These fields are not needed to interpret the event's decision.
- Generate random archive-local aliases for valid profile and attempt tokens in relay health JSONL. Reuse each alias within one render so repeated records remain groupable. Keep `unavailable` for missing or invalid values.
- Keep existing JSON field names and archive schema version. The values have a privacy behavior change; no reader schema migration is needed.

## Contracts and ownership

- Affected module: `:core:diagnostics` export redactor, relay health JSONL builder, and focused tests.
- No Rust crate, JNI, protobuf, database schema, or serialized shared file changes.
- Other agents own all remaining diagnostics paths. This worktree owns only the three files named in the portfolio task and its task/OpenSpec artifacts.

## Risks / Trade-offs

- Archive recipients cannot correlate relay profile and attempt IDs across exports. That is the intended privacy boundary.
- A random alias collision is negligible with a 128-bit UUID source. Tests check grouping and cross-archive separation.

## Migration Plan

New archives use archive-local aliases. Existing archives remain unchanged. Rollback would restore cross-archive linkage and is therefore not a safe privacy fallback. Validate with focused archive tests, `./taskctl openspec cli validate`, and `./taskctl validate`.
