## Context

The shared `ripdpi-protocol-detect` classifier is already used by socket Lua. TUN dissection only recognizes QUIC, and its protocol matcher uses transport alone. See proposal.md and the UDP requirements.

## Goals / Non-Goals

- Goal: select UDP rules by the existing payload classification and supply that classification to TUN strategies.
- Non-goal: rewrite classifier heuristics, add DNS configuration names, extend MTProto UDP matching, change TCP matching, or change raw emission.

## Decisions

- Classify each incoming UDP payload once for rule matching. Pass the typed result to the matcher; preserve empty/Any filters and port/host predicates.
- Reuse the same classifier in TUN dissection with actual ports. Keep parsed QUIC version enrichment and current marker extraction.
- Use packet-handler regression tests across IPv4 and IPv6, and actual Lua execution tests for protocol/subtype context. Existing QUIC tests must use the maintained QUIC producer instead of arbitrary bytes.
- Keep the existing signature heuristics. This work connects them; it does not claim complete protocol validation or DPI evasion.

## Contracts and ownership

- Primary owns `ripdpi-tunnel-intercept`, its single Cargo.lock dependency edge, and all task/specification files in `feat/udp-classification`.
- Discovery, test, and review agents are read-only. No parallel writers own serialized files.
- No public configuration, JNI, Kotlin, storage, or root-helper contract changes. Add only an existing internal workspace crate dependency.

## Risks / Trade-offs

- Old rules may have relied on transport-only matching. Empty/Any filters still express that behavior.
- Protocol heuristics can return false positives. Preserve their existing limits and test short/unknown inputs and neighboring protocols.
- QUIC host parsing and markers must remain intact. Existing real QUIC Initial and Lua marker tests are regression oracles.

## Migration Plan

No stored-data migration. Revert the scoped commit to restore the previous behavior.

Gates: reproduce the unknown-UDP scoped-rule defect; `cargo test --locked -p ripdpi-tunnel-intercept -p ripdpi-protocol-detect -p ripdpi-proxy-runtime-desync-adapter`; affected Clippy and fmt; native architecture contracts and architecture health; Android-target compile or test evidence; strict task validation; independent review; rebase, rerun gates, fast-forward main, push, and verify remote SHA.
