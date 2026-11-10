# Spec Version

This crate's wire format is pinned against the canonical AnyTLS Go implementation and protocol documentation.

- **Upstream repo:** https://github.com/anytls/anytls-go
- **Upstream tag:** main (no release tag for the protocol documentation)
- **Upstream commit:** 2012ef89768409f45437f1c06a7af5f6eea402ad
- **Last reviewed:** 2026-05-27
- **Owner:** unassigned

## Scope

This crate implements the AnyTLS client/outbound role only, including:

- TLS client transport over BoringSSL with the configured browser profile (TLS 1.2-1.3 range)
- First-packet password authentication with padding0
- Session frame codec and multiplexed TCP streams
- UDP-over-TCP framing
- Padding scheme parsing, padding-md5, per-packet size rules, stop thresholds, check-mark handling, and update handling

AnyTLS server/inbound mode and non-TLS transport substrates are intentionally out of scope.

## Deliberate divergences from upstream

- Cumulative 65535 cap: `parse_packet_rule` skips a range part that would take one packet rule past 65535 bytes in total. Upstream has no such cap. The cap keeps a packet-0 length inside the big-endian uint16 wire field and bounds the size of one padded record.
- Leading `c` in the packet-0 rule: upstream sends the check mark (-1) as length 65535 with no bytes behind it, which breaks the stream. This crate sends padding length 0.
- Range draw and padding0 follow upstream: sizes come from `[min, max)` for `min < max`, and padding0 uses the first packet-0 part whatever `stop` is. Session padding for packets 1 and above honors `stop`.

## Drift policy

`anytls-go` is normative and wins conflicts. The scheduled upstream spec-watch workflow tracks this pin; cross-interop against the Go implementation should remain a nightly oracle when network access and the upstream CLI are available.
