# ripdpi-tls-profiles

**Responsibility:** TLS client fingerprint profiles and the outbound ECH facade used by workspace-owned TLS clients.

The ECH facade must be a small integration layer, not a protocol implementation. It owns the chain from encrypted-DNS HTTPS/SVCB lookup to `ECHConfigList` selection, backend-specific TLS configuration, rejection retry handling, and GREASE when no real config is available. It must reuse `ripdpi-dns-resolver::https_service_binding` for HTTPS/SVCB parsing, `ripdpi-diagnostics-dns::cdn_ech` for CDN fallback material, the existing encrypted-DNS resolver path, and the `boring` 5.1.0 client-ECH API.

ECH HTTPS RR lookup must be encrypted-DNS-only. The facade must call `resolve_https_ech_configs_via_encrypted_dns_with_endpoint` or a directly equivalent encrypted-DNS path and must refuse plaintext DNS fallback for inner-SNI names. A plaintext HTTPS RR query leaks the target name before TLS and defeats the privacy property ECH is being wired to preserve.

When a real `ECHConfigList` is available, the facade configures the outbound TLS backend with that list and requires post-handshake ECH acceptance evidence where the backend exposes it. When no real config is published or resolvable for an in-scope backend, the facade enables ECH GREASE rather than sending a plain ClientHello. On `SSL_R_ECH_REJECTED`, the facade verifies the public name certificate context, consumes server retry configs, retries once, and reports failure instead of silently falling back to non-ECH.

Per-backend opt-out is allowed only as an explicit policy input for compatibility or unsupported backend reasons. Opt-out must be visible in tests and must not become the default.

## Non-Goals

- VLESS Reality and ShadowTLS outbound are out of scope for this facade because they have their own SNI-cover schemes and need separate analysis.
- Server-side ECH is out of scope.
- Re-implementing HTTPS/SVCB RR parsing is out of scope; use `ripdpi-dns-resolver::https_service_binding`.
- Re-implementing CDN ECH update/fallback logic is out of scope; use `ripdpi-diagnostics-dns::cdn_ech`.
- Re-implementing HPKE or ECH internals is out of scope; use the TLS backend APIs.
