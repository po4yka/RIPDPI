---
title: Replace hand-rolled DNS TCP length-prefix framing with tokio-util LengthDelimitedCodec
type: task
status: backlog
area: dns
priority: medium
owner: unassigned
parent: consolidate-rust-manual-implementations-with-vendored-deps
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Replace hand-rolled DNS TCP length-prefix framing with tokio-util LengthDelimitedCodec #repo/RIPDPI #area/dns #status/backlog 🔼

## Summary

`ripdpi-dns-resolver/src/transport/framing.rs` manually writes and reads a 2-byte big-endian length prefix (RFC 1035 §4.2.2) using `write_all`/`read_exact`. `tokio_util::codec::LengthDelimitedCodec` provides exactly this contract. `tokio-util` is already a transitive dep in the workspace (via `ripdpi-tunnel-android`); adding the `codec` feature to `ripdpi-dns-resolver` is the only change needed to the dependency tree.

## Implementation steps

1. Add `tokio-util = { workspace = true, features = ["codec"] }` to `ripdpi-dns-resolver/Cargo.toml`. Ensure `tokio-util` has the `codec` feature in `[workspace.dependencies]`.
2. Replace `send_dns_message` / `recv_dns_message` in `framing.rs` with a `LengthDelimitedCodec`:
   ```rust
   let codec = LengthDelimitedCodec::builder()
       .length_field_length(2)
       .big_endian()
       .new_codec();
   let mut framed = Framed::new(stream, codec);
   framed.send(Bytes::from(msg)).await?;
   let response = framed.next().await??;
   ```
3. Delete `framing.rs` manual impl; update callers in `transport/tcp/` to use the framed stream directly.
4. `cargo nextest run -p ripdpi-dns-resolver`.

## Acceptance criteria

- [ ] `framing.rs` manual length-prefix logic deleted.
- [ ] `tokio-util` `codec` feature enabled for `ripdpi-dns-resolver`.
- [ ] `cargo nextest run -p ripdpi-dns-resolver` passes.
- [ ] DoT and DNS-over-TCP-via-SOCKS5 paths covered by existing integration tests in `local-network-fixture`.
