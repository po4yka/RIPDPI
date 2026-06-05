---
title: Add format-transforming encryption (Marionette-style) for protocol shape-shifting
type: task
status: backlog
area: rust-native
priority: low
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-16
updated: 2026-06-05
---

## Summary

Implement Format-Transforming Encryption (FTE) that reshapes ciphertext bytes to match a regular-expression-defined cover format (e.g. "looks like syslog UDP", "looks like HTTP/1.0 GET response"). Combined with a state-machine driver in the Marionette tradition, this defeats DPI classifiers that work by matching the wire bytes against a known set of legitimate-protocol regexes.

## Context

Marionette (USENIX Security 2015) demonstrated that ciphertext can be remapped through ranked-encoded automata so it matches any regex-describable protocol. The remapping is reversible. The cost is some expansion (typically 1.2-1.8x) and a substantial regex/ state-machine compilation step at startup.

RIPDPI's transports today all look like *some* protocol on the wire (HTTPS, QUIC, MTProto). FTE adds the ability to look like *any* protocol the operator specifies — useful for niches like "look like internal industrial-control traffic" or "look like a specific videoconferencing app" where the standard cover protocols are themselves blocked.

## Acceptance criteria

- [ ] New crate `ripdpi-fte` with a ranked-encoding FTE implementation (encode/decode against a compiled DFA).
- [ ] At least two preset profiles: `http_get_response` and `sip_invite`.
- [ ] Round-trip test: encode random payload through each profile, assert wire bytes match the regex, decode back to payload.
- [ ] Telemetry: bytes-expanded vs bytes-real.
- [ ] Documentation under `docs/native/` covers throughput cost and intended use cases (operator-controlled cover format).

## Risks / open questions

- FTE bytes have characteristic statistical properties (uniform byte distribution inside the regex's character classes). Modern ML-based DPI can detect this. FTE is therefore a *complement* to other obfuscation, not a primary defense.
- Regex compilation is operator-controlled; a poorly chosen regex (e.g. ambiguous DFA) can blow up at compile time. Document the constraints.

## Links

- Marionette: https://www.usenix.org/conference/usenixsecurity15/technical-sessions/presentation/dyer
- [[add-constant-rate-traffic-shaping-voip-camouflage]]

## Work log

- 2026-06-05: No implementation exists — `ripdpi-fte` crate absent from native/rust/crates/, no FTE/Marionette code or docs anywhere in the repo; all acceptance criteria remain open.
