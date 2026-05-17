# Spec Version

This crate's wire format (XHTTP transport, FinalMask Sudoku padding, gRPC framing) is pinned against the following upstream reference.

- **Upstream repo:** https://github.com/XTLS/Xray-core
- **Upstream tag:** v1.260206.0
- **Upstream commit:** unverified-as-of-2026-05-15
- **Last reviewed:** 2026-05-15
- **Owner:** unassigned

## Scope

This crate implements the XHTTP transport (HTTP-tunneled), FinalMask Sudoku-based padding, and the gRPC length-prefixed framing used by Xray client outbounds. VLESS+REALITY itself is in `ripdpi-vless`.

## Drift policy

xray-core ships XHTTP-affecting changes on its release cadence. Known deadlines:

- xray-core v26.1.18 — XHTTP+REALITY combination breakage

Watched weekly via `.github/workflows/upstream-spec-watch.yml`.
