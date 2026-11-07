# Change: Use Hickory for ECH DNS wire messages

Task ID: `DNS-1794036623422123`

## Why

ECH DNS queries and replies must follow the same DNS wire rules as the main resolver without a second handwritten parser. The 2026-11-07 audit identifies this as finding 10 at source revision 202f970.

## What Changes

- Build and decode messages with existing Hickory types. Reuse or expose the smallest safe domain adapter without reversing crate dependency direction.
- example.com and example.com. produce equivalent valid DNS questions and retain the requested transaction ID.
- Wrong-query, malformed, truncated, and invalid-owner replies remain rejected by the shared validation contract.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; Keep query ID, question/owner/type/CNAME checks, intended IPv4-only behavior, bounded parsing, and stable error categories. Existing reply validation must remain.

## Capabilities

### New Capabilities

- `standard-components/use-hickory-for-ech-dns-wire`: ECH DNS queries and replies must follow the same DNS wire rules as the main resolver without a second handwritten parser.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `native/rust/crates/ripdpi-ech-dns/src/wire.rs`
- `native/rust/crates/ripdpi-dns-resolver/src/transport/wire.rs`

Use only existing platform or workspace libraries. Check the direct module dependency before editing. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
