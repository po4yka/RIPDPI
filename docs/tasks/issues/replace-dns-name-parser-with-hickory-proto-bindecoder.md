---
title: Replace hand-rolled DNS name label parser in ripdpi-tunnel-core with hickory-proto BinDecoder
type: task
status: backlog
area: dns
priority: low
owner: unassigned
parent: consolidate-rust-manual-implementations-with-vendored-deps
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Replace hand-rolled DNS name label parser in ripdpi-tunnel-core with hickory-proto BinDecoder #repo/RIPDPI #area/dns #status/backlog 🔽

## Summary

`ripdpi-tunnel-core/src/dns_cache/parser.rs` implements `skip_dns_name` and `dns_question_end` — a partial DNS wire-format parser that navigates label sequences and pointer compression (RFC 1035 §4.1.4) to locate question offsets. `hickory-proto` is already a workspace dep in `ripdpi-tunnel-core`; `hickory_proto::wire::dns_encode::BinDecoder` with `Name::read` handles label parsing and pointer-compression edge cases correctly, including loop detection.

## Implementation steps

1. Replace `skip_dns_name` in `parser.rs` with:
   ```rust
   use hickory_proto::{rr::Name, wire::dns_decode::BinDecoder};
   let mut decoder = BinDecoder::new(buf);
   Name::read(&mut decoder)?; // advances past the name
   let pos = decoder.index();
   ```
2. Remove `dns_question_end` and inline the offset calculation using `decoder.index()` after `Name::read`.
3. Update `dns_cache/intercept.rs` (or wherever `dns_question_end` is called) to use the new API.
4. Delete the manual `parser.rs` pointer-compression loop.
5. Add a unit test with a DNS packet containing a compression pointer to guard against regression.
6. `cargo nextest run -p ripdpi-tunnel-core`.

## Acceptance criteria

- [ ] Manual pointer-compression loop in `parser.rs` deleted.
- [ ] `Name::read` from `hickory-proto` used for name traversal.
- [ ] Unit test covers pointer-compressed DNS name parsing.
- [ ] `cargo nextest run -p ripdpi-tunnel-core` passes.
- [ ] No new dep added (hickory-proto already in workspace).
