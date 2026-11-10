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
- Session frame codec; TCP streams, one per session, with an idle-session pool
- UDP-over-TCP framing
- Padding scheme parsing, padding-md5, per-packet size rules, stop thresholds, check-mark handling, and update handling

AnyTLS server/inbound mode and non-TLS transport substrates are intentionally out of scope.

## Deliberate divergences from upstream

- Cumulative 65535 cap: `parse_packet_rule` skips a range part that would take one packet rule past 65535 bytes in total. Upstream has no such cap. The cap keeps a packet-0 length inside the big-endian uint16 wire field and bounds the size of one padded record.
- Leading `c` in the packet-0 rule: upstream sends the check mark (-1) as length 65535 with no bytes behind it, which breaks the stream. This crate sends padding length 0.
- Range draw and padding0 follow upstream: sizes come from `[min, max)` for `min < max`, and padding0 uses the first packet-0 part whatever `stop` is. Session padding for packets 1 and above honors `stop`.

- One stream per session: the reference client (`proxy/session/client.go`) gives each active stream its own session and pools idle ones. This crate does the same: it reuses the idle session with the highest `seq`, expires sessions idle for 30 s (sweep every 30 s), and the carrier reader waits for a slow stream reader (`send().await`) instead of dropping data. The same applies to a UDP-over-TCP association: it is one byte stream, so datagrams cannot be dropped mid-stream without breaking the framing, and a slow consumer stalls that carrier.
- Version detection: a fresh session waits at most 2 s for `cmdServerSettings` (a v2 server sends it right after the first packet). Silence means protocol v1 for that session only: no SYNACK wait, no `HeartRequest` probe, and the stream is returned once SYN and PSH are queued, as `session.go:145-171` does. The version is never carried to other sessions.
- Unknown commands are consumed by their declared length and ignored, as `session.go:357` does, except as the first frame of a session, where they mean the peer is not an AnyTLS server (`AuthenticationRejected`).
- A stream ends cleanly only on a peer `cmdFIN`; a carrier that dies mid-stream, or a late SYNACK rejection, is reported to the application as a reset.

## Drift policy

`anytls-go` is normative and wins conflicts. The scheduled upstream spec-watch workflow tracks this pin; cross-interop against the Go implementation should remain a nightly oracle when network access and the upstream CLI are available.
