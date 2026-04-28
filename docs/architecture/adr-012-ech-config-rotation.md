# ADR-012: ECH Config Rotation Strategy

**Status:** Partial — Phase 1 (abstraction + bundled source) landed; Phase 2 (RemoteEchConfigSource) pending.

**Date:** 2026-04-27

---

## Context

`ripdpi-monitor` uses Encrypted Client Hello (ECH) when connecting to Cloudflare-hosted endpoints.
When encrypted DNS (DoH/DoQ) is blocked, the engine falls back to a hardcoded ECHConfigList
(`CLOUDFLARE_ECH_CONFIG_LIST` in `cdn_ech.rs`, captured 2026-04-08).

Cloudflare rotates ECH public keys periodically (observed cadence: every few months).  After a
rotation:

1. The hardcoded config contains a stale public key.
2. The TLS handshake sends an `encrypted_client_hello` extension with the old config.
3. Cloudflare returns a `retry_configs` extension with fresh keys and the handshake falls back to
   plaintext SNI — ECH is silently ineffective until the binary is updated.

This is a silent security degradation: no error, no user notification, just a missed privacy
property.

---

## Decision

### Phase 1 — Abstraction (this ADR, landed)

Introduce three components in `cdn_ech.rs`:

| Component | Role |
|---|---|
| `EchConfigSource` trait | Single method `fetch() -> Result<Vec<u8>, EchSourceError>` |
| `BundledEchConfigSource` | Returns `CLOUDFLARE_ECH_CONFIG_LIST` — always available, never fails |
| `RemoteEchConfigSource` | Stub; returns `EchSourceError::NotImplemented` |
| `CdnEchUpdater<P, F>` | TTL cache (default 24 h), tries primary → fallback, last-resort to bundled bytes |

The existing call site in `tls.rs` (`cdn_config.ech_config_list.to_vec()`) is **not changed** —
the static `CdnEchConfig` struct is still the direct production path.  `CdnEchUpdater` is wired
only when a scheduler is ready (Phase 2).

### Phase 2 — Remote source (not yet implemented)

`RemoteEchConfigSource::fetch` will perform a DoH HTTPS-RR (type 65) query:

- Query name: `cloudflare-dns.com` (or `_dns.resolver.arpa`)
- DoH endpoint: `https://1.1.1.1/dns-query` (Cloudflare public resolver, no project backend)
- Response parsing: reuse `extract_ech_config_list_from_https_response` from `dns.rs`
- Validate: length prefix + version `0xfe0d` before storing

Constraints:
- **No project backend** — source must be a public DoH endpoint, not a RIPDPI-operated server.
- **Fail-secure** — if fetch fails or response is invalid, keep the cached/bundled config; do not
  clear ECH support.
- **Scheduler integration** — `CdnEchUpdater::refresh()` is the entry point; call it from a
  WorkManager periodic job (≥ 24 h interval, requires network).

### Phase 3 — Cache persistence (optional, future)

`CdnEchUpdater` currently holds the cache only in memory (`std::sync::Mutex<Option<CachedEch>>`).
A future step could persist the refreshed config to `EncryptedSharedPreferences` so it survives
process restarts, reducing the window where stale bundled config is used after a key rotation.

---

## Consequences

**Positive:**
- The abstraction is in place; Phase 2 can land without touching `tls.rs` or `CdnEchConfig`.
- `BundledEchConfigSource` as fallback guarantees ECH is never disabled due to a refresh failure.
- `CdnEchUpdater` is unit-tested (cache hit, fallback-on-failure, both-sources-fail last resort).

**Negative / Risks:**
- The static config can still go stale between binary releases.  Phase 2 is needed to fully close
  this gap.
- `RemoteEchConfigSource` does nothing yet — a stale config is the current production behaviour,
  unchanged by Phase 1.

---

## Alternatives Considered

**Bundle a cron-refreshed config file in assets.**  Rejected: requires a CI job to keep the file
current and a binary update to distribute it; no better than the current constant.

**Fetch from a RIPDPI-operated endpoint.**  Rejected: violates the no-backend rule (see
`CLAUDE.md`).  Cloudflare's own public DoH resolver is the correct source since the ECH config is
Cloudflare's public data.

**Use `async_trait` for `EchConfigSource`.**  Not available in workspace deps.  The sync trait is
sufficient — the updater is called from a blocking context or a WorkManager job thread, not from
a hot async path.

---

## Related

- `native/rust/crates/ripdpi-monitor/src/cdn_ech.rs` — implementation
- `native/rust/crates/ripdpi-monitor/src/dns.rs:extract_ech_config_list_from_https_response` — reuse in Phase 2
- `native/rust/crates/ripdpi-monitor/src/tls.rs:resolve_opportunistic_ech` — current call site
- ADR-009: TLS profiles and ECH bootstrap context
