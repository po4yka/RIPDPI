# Spec Version

Reference versions are scoped to each feature. They do not assert parity with every option in an upstream release.

## xHTTP reference

- **Upstream repo:** https://github.com/XTLS/Xray-core
- **Upstream tag:** v26.2.6 (module tag v1.260206.0)
- **Upstream commit:** 12ee51e4bb1d02ece4ef4b7114efa2bcdc130995
- **Last reviewed:** 2026-11-11
- **Owner:** Transport maintainer

The xHTTP session driver implements H2 `stream-up` and `stream-one` with raw VLESS payloads. The crate also provides Finalmask transforms and a separate gRPC framing codec. VLESS and REALITY are owned by `ripdpi-vless`.

The xHTTP release and module tags resolve to the commit above. The wire reference is [the pinned SplitHTTP source](https://github.com/XTLS/Xray-core/tree/12ee51e4bb1d02ece4ef4b7114efa2bcdc130995/transport/internet/splithttp). The TRN-04 audit checked its mode, URL, header, pairing and XMUX contracts. Later REALITY runtime compatibility uses a separate pinned peer test; this record does not certify every upstream option or any Android Xray artifact.

## Finalmask reference

- **Upstream repo:** https://github.com/XTLS/Xray-core
- **Upstream tag:** v26.10.21
- **Upstream commit:** 1b1647cd9ddf0abc34b981d4611fc03bcac1ca74
- **Last reviewed:** 2026-11-11
- **Owner:** Native transport maintainers

The Finalmask reference is `transport/internet/finalmask` at this exact commit. Sudoku uses classic uplink tuples and packed downlink groups with the default entropy layout. ASCII and custom tables are outside this subset. The xHTTP reference above predates this Sudoku implementation.

The REALITY header peer requires the repository-owned `docs/examples/finalmask/xray-header-closewrite.patch` adapter. It restores the server wrapper half-close method required by REALITY without changing wire bytes. Unpatched header+REALITY is not claimed compatible.

Header and fixed-length noise prefixes map to Xray TCP `header-custom` client sequences with an empty server sequence. Xray's `noise` type is UDP-only. RIPDPI's random range selects a byte count; Xray's `randRange` selects byte values. See [compatibility and local acceptance](../../../../docs/native/finalmask-compatibility.md).

This reference does not change the separate packaged libXray version in `gradle/libs.versions.toml`.

## Drift policy

The separate Xray provider rejects known incompatible REALITY versions through its admission validator. Recheck that policy and the pinned peer when either upstream changes.

The weekly workflow checks field presence and format. It does not check upstream wire drift. Re-run the local peer acceptance command before changing the Finalmask reference. Record the command and result for each supported carrier mode; local byte tests do not establish Cloudflare compatibility or TCP segmentation.
