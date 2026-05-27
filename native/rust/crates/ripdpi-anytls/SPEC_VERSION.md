# Spec Version

This crate's wire format is pinned against the canonical AnyTLS Go implementation and protocol documentation.

- **Upstream repo:** https://github.com/anytls/anytls-go
- **Upstream tag:** main (no release tag for the protocol documentation)
- **Upstream commit:** 2012ef89768409f45437f1c06a7af5f6eea402ad
- **Last reviewed:** 2026-05-27
- **Owner:** unassigned

## Scope

This crate implements the AnyTLS client/outbound role only, including:

- TLS 1.3 client transport over BoringSSL
- First-packet password authentication with padding0
- Session frame codec and multiplexed TCP streams
- UDP-over-TCP framing
- Padding scheme parsing, padding-md5, per-packet size rules, stop thresholds, check-mark handling, and update handling

AnyTLS server/inbound mode and non-TLS transport substrates are intentionally out of scope.

## Drift policy

`anytls-go` is normative and wins conflicts. The scheduled upstream spec-watch workflow tracks this pin; cross-interop against the Go implementation should remain a nightly oracle when network access and the upstream CLI are available.
