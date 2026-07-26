# ripdpi-tls-profiles

**Responsibility:** TLS client fingerprint profiles plus the policy/configuration seam used by the outbound ECH implementation in `ripdpi-ech-dns`.

This crate owns ECH policy, backend configuration, rejection/retry semantics,
and GREASE decisions. Encrypted HTTPS/SVCB lookup and `ECHConfigList` discovery
belong to `ripdpi-ech-dns`; this crate consumes the resulting policy input and
must not duplicate DNS transport or record parsing.

ECH HTTPS RR lookup must be encrypted-DNS-only. `ripdpi-ech-dns` enforces that
rule and must refuse plaintext DNS fallback for inner-SNI names. A plaintext
HTTPS RR query leaks the target name before TLS and defeats the privacy property
ECH is being wired to preserve.

When a real `ECHConfigList` is available, the facade configures the outbound TLS backend with that list and requires post-handshake ECH acceptance evidence where the backend exposes it. When no real config is published or resolvable for an in-scope backend, the facade enables ECH GREASE rather than sending a plain ClientHello. On `SSL_R_ECH_REJECTED`, the facade verifies the public name certificate context, consumes server retry configs, retries once, and reports failure instead of silently falling back to non-ECH.

Per-backend opt-out is allowed only as an explicit policy input for compatibility or unsupported backend reasons. Opt-out is scoped to the requested backend, must skip HTTPS RR lookup for that backend, must be visible in tests, and must not become the default. An opt-out does not authorize plaintext HTTPS RR fallback for ECH inner-SNI names; it means that backend is intentionally outside the ECH attempt for that connection.

## Non-Goals

- VLESS Reality and ShadowTLS outbound are out of scope for this facade because they have their own SNI-cover schemes and need separate analysis.
- VLESS Reality specifically does not use real ECH; the accepted policy is GREASE-only parity where profile evidence supports it. See [ADR 0001](../../../../docs/adr/0001-reality-ech.md).
- Server-side ECH is out of scope.
- Re-implementing HTTPS/SVCB resolution is out of scope; use `ripdpi-ech-dns`.
- Re-implementing HPKE or ECH internals is out of scope; use the TLS backend APIs.
