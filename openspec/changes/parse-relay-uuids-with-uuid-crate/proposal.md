# Change: Parse relay UUIDs with the uuid crate

Task ID: `RLY-1794036606000501`

## Why

Use the uuid crate for VLESS, xHTTP, and Cloudflare origin parsing. Keep their canonical and compact forms and preserve existing TUIC syntax. The 2026-11-07 audit identifies this as finding 8 (attachment 4) at source revision 202f970.

## What Changes

- Replace arbitrary dash removal and hex decoding with Uuid::parse_str and explicit canonical-dashed or compact input-shape checks.
- Canonical and compact forms of one UUID produce the same independently specified 16 bytes in each relay.
- -550e8400e29b41d4a716446655440000 and other misplaced-dash forms fail validation.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; The three migrated parsers accept canonical-dashed and compact input without trimming. They reject URN and braced forms and misplaced dashes. TUIC keeps Uuid::parse_str(config.uuid.trim()), including existing URN and braced forms. Preserve credential bytes and error redaction.

## Capabilities

### New Capabilities

- `standard-components/parse-relay-uuids-with-uuid-crate`: Use the uuid crate for VLESS, xHTTP, and Cloudflare origin parsing. Keep their canonical and compact forms and preserve existing TUIC syntax.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `native/rust/crates/ripdpi-vless/src/config.rs`
- `native/rust/crates/ripdpi-xhttp/src/config.rs`
- `native/rust/crates/ripdpi-cloudflare-origin/src/config.rs`
- `native/rust/crates/ripdpi-tuic/src/protocol.rs`

Use only existing platform or workspace libraries. Check the direct module dependency before editing. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
