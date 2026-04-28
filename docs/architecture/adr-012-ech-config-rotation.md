# ADR-012: ECH Config Rotation Strategy

**Status:** Phase 1 (abstraction + bundled source), Phase 2
(`RemoteEchConfigSource`), and Phase 3 cache-persistence API landed. The
WorkManager scheduler hookup and the Kotlin
`EncryptedSharedPreferences`-backed cache that consumes the persistence
API are still deferred — both share infrastructure with the ADR-011
shared-priors transport (WorkManager is not yet a project dependency)
and will land together once that infrastructure is introduced.

**Date:** 2026-04-27 (Phase 1), 2026-04-28 (Phase 2 and Phase 3 cache API)

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

### Phase 2 — Remote source (landed 2026-04-28)

`RemoteEchConfigSource::fetch` performs a real DoH HTTPS-RR (type 65) query
through the existing `crate::dns` plumbing:

- Default query name: `cloudflare-dns.com` (overridable via `with_domain`).
- Default DoH resolver: `cloudflare`, looked up via the shared
  `encrypted_dns_endpoint_for_resolver_id` helper (overridable via
  `with_resolver`). No project-operated server is involved.
- Transport: `TransportConfig::Direct { route_experiment: None }`. The
  encrypted-DNS resolver does the TLS handshake, certificate validation,
  and response decoding via `boring` and the existing bootstrap-IP list.
- Response parsing: reuses `resolve_https_ech_configs_via_encrypted_dns_with_endpoint`
  → `extract_ech_config_list_from_https_response`.
- Validation: new `validate_ech_config_list_bytes` enforces the 2-byte length
  prefix and the `0xfe0d` version before the bytes leave the source.
- Fail-secure: any failure path — DNS exchange error, missing ECH SvcParam,
  malformed list — surfaces as `EchSourceError::InvalidConfig`, so
  `CdnEchUpdater::refresh()` keeps the previously cached or bundled config.

Constraints (still upheld):
- **No project backend** — Cloudflare's public DoH resolver is the source.
- **Fail-secure** — invalid responses do not clear ECH support.
- **Scheduler integration** — `CdnEchUpdater::refresh()` is the entry point;
  the WorkManager periodic-job hookup is the remaining open item below.

### Phase 3 — Cache persistence (Rust API landed 2026-04-28)

The cache now exposes a persistence shape so Kotlin can durably store the
refreshed bytes between process restarts:

- `CachedEch` carries `fetched_at_unix_ms: u64` alongside the existing
  monotonic `Instant` anchor. The `Instant` keeps the TTL comparison
  immune to wall-clock jumps; the wall-clock millis exists purely so the
  cache can round-trip through `EncryptedSharedPreferences`.
- `CdnEchUpdater::snapshot_for_persistence() -> Option<CachedEchSnapshot>`
  returns `(config_bytes, fetched_at_unix_ms)` for the current entry, or
  `None` when the cache is cold (so a stale-but-useful prior persisted
  entry isn't accidentally clobbered).
- `CdnEchUpdater::seed_from_persisted(config, fetched_at_unix_ms)`
  validates the bytes via `validate_ech_config_list_bytes` (fail-secure
  — an invalid persisted entry is rejected and the existing in-memory
  cache is left untouched), then reconstructs an equivalent `Instant`
  from the wall-clock timestamp so the TTL window evaluates as it would
  have without a restart. A 6 h-old persisted entry against a 24 h TTL
  is still considered fresh after the restart.

Verified by 6 new unit tests (empty-cache snapshot, seed→snapshot
round-trip, malformed-bytes rejection preserves prior cache, seeded
entry served via `current_config` while fresh, future-timestamp clamp,
past-timestamp age-preservation drift bound).

### Phase 3 — Kotlin glue (still deferred)

The remaining work is Android-side and shares infrastructure with the
ADR-011 shared-priors transport:

- `EncryptedSharedPreferences`-backed cache that calls
  `snapshot_for_persistence` after every successful `refresh()` and
  feeds `seed_from_persisted` at app startup.
- `CdnEchRefreshWorker` (`CoroutineWorker`) scheduled as a 24 h
  `PeriodicWorkRequest` via `WorkManager.enqueueUniquePeriodicWork`.
- JNI bridge exposing `seed_from_persisted` / `snapshot_for_persistence`
  / `refresh` against a singleton updater instance.
- Hilt-WorkManager integration (the project does not yet depend on
  `androidx.work` — this lands as part of introducing WorkManager
  project-wide, jointly with the shared-priors refresher in ADR-011).

---

## Consequences

**Positive (post Phase 2, 2026-04-28):**
- `RemoteEchConfigSource` performs a real DoH HTTPS-RR query and validates
  the response shape before handing it to the cache. The static
  `CLOUDFLARE_ECH_CONFIG_LIST` no longer drifts silently between binary
  releases once a scheduler periodically calls `CdnEchUpdater::refresh()`.
- `BundledEchConfigSource` as fallback still guarantees ECH is never
  disabled due to a refresh failure; `validate_ech_config_list_bytes`
  prevents a malformed remote response from poisoning the cache.
- `CdnEchUpdater` is unit-tested for cache hit, fallback-on-failure,
  both-sources-fail, and the new "remote bytes round-trip through the
  cache" path. The validator is unit-tested for accept-bundled, reject-
  short, reject-length-mismatch, and reject-unknown-version inputs.

**Risks / not-yet-validated:**
- The WorkManager periodic-job hookup is still pending; without it, the
  cache stays cold across process restarts (Phase 3) and only refreshes on
  in-process demand. Tracked in the open follow-ups list at the bottom of
  this ADR.
- A live DoH query depends on network reachability. The fail-secure design
  means a blocked DoH path falls back to the bundled config rather than
  losing ECH, but on a long-blocked network the bundled config can still
  drift; Phase 3 cache persistence is the mitigation.

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
