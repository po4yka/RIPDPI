# Spec Version

This crate's wire format (XHTTP transport, FinalMask Sudoku padding, gRPC framing) is pinned against the following upstream reference.

- **Upstream repo:** https://github.com/XTLS/Xray-core
- **Upstream tag:** v26.2.6 (module tag v1.260206.0)
- **Upstream commit:** 12ee51e4bb1d02ece4ef4b7114efa2bcdc130995
- **Last reviewed:** 2026-11-11
- **Owner:** Transport maintainer

## Scope

The xHTTP session driver implements H2 `stream-up` and `stream-one` with raw
VLESS payloads. The crate also provides FinalMask padding and a separate gRPC
framing codec. VLESS and REALITY are owned by `ripdpi-vless`.

Both recorded upstream tags resolve to the commit above. The wire reference is
[the pinned SplitHTTP source](https://github.com/XTLS/Xray-core/tree/12ee51e4bb1d02ece4ef4b7114efa2bcdc130995/transport/internet/splithttp).
The TRN-04 audit checked its mode, URL, header, pairing and XMUX contracts.
Later REALITY runtime compatibility uses a separate pinned peer test; this
record does not certify every upstream option or any Android Xray artifact.

## Drift policy

xray-core ships XHTTP-affecting changes on its release cadence. The separate Xray
provider rejects known incompatible REALITY versions through its admission
validator. Recheck that policy and the pinned peer when either upstream changes.

The weekly workflow validates presence, formatting, and recorded pins; it does
not fetch xray-core or detect upstream wire drift automatically.
