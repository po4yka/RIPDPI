# Strategy-Pack And TLS Catalog Operations

This runbook describes how RIPDPI's remote control plane works and how to maintain it safely.

## What The Catalog Controls

The strategy-pack catalog is the app's remote configuration surface for:

- pack selection and strategy recommendations
- TLS profile-set selection and rotation policy
- morph policies
- transport modules
- rollout metadata
- feature flags for transport behavior

Relevant feature flags in schema v3:

- `cloudflare_publish`
- `cloudflare_consume_validation`
- `finalmask`
- `masque_cloudflare_direct`
- `naiveproxy_watchdog`

## Storage And Sources

Bundled fallback catalog:

- asset path: `app/src/main/assets/strategy-packs/catalog.json`

Downloaded snapshot cache:

- file path: `filesDir/strategy-packs/catalog.snapshot.json`

Manifest path pattern:

- `https://raw.githubusercontent.com/poyka/ripdpi-strategy-packs/main/<channel>/manifest.json`

Default channel and refresh behavior:

- channel: `stable`
- refresh policy: `automatic`

User-facing settings are modeled by:

- `StrategyPackSettingsModel.channel`
- `StrategyPackSettingsModel.pinnedPackId`
- `StrategyPackSettingsModel.pinnedPackVersion`
- `StrategyPackSettingsModel.refreshPolicy`
- `StrategyPackSettingsModel.allowRollbackOverride` (debug-only local override)

## Verification Pipeline

The repository verifies downloaded catalogs in this order:

1. Download manifest.
2. Download catalog payload to a temporary file.
3. Compute SHA-256 while streaming to disk.
4. Compare the computed checksum to `catalogChecksumSha256`.
5. Verify `catalogSignatureBase64` using the trusted ECDSA public key.
6. Parse the catalog payload.
7. Reject it if app or native compatibility fails.
8. Enforce downloaded-catalog anti-rollback policy:
   - require `sequence > 0`
   - require parseable UTC `issuedAt`
   - reject `issuedAt` older than 30 days
   - reject `sequence <= last accepted downloaded sequence` for the same channel unless the debug override is enabled
9. Cache the verified snapshot and expose it as `downloaded`.
10. Fall back to the bundled asset if the cached or downloaded snapshot is incompatible.

Important compatibility fields:

- `schemaVersion`
- `minAppVersion`
- `minNativeVersion`

Current schema version:

- `StrategyPackCatalogSchemaVersion = 3`

Signature algorithm:

- `SHA256withECDSA`

Trusted key id:

- `ripdpi-prod-p256`

## Catalog Structure

The current catalog schema includes:

- `sequence`
- `issuedAt`
- `packs`
- `tlsProfiles`
- `morphPolicies`
- `hostLists`
- `transportModules`
- `featureFlags`
- `rollout`

TLS-specific fields live in `StrategyPackTlsProfileSet`:

- `id`
- `title`
- `catalogVersion`
- `allowedProfileIds`
- `rotationEnabled`
- `notes`

## TLS Refresh Cadence

Bundled TLS catalog review is now tracked through:

- log file: `docs/strategy-pack-tls-refresh-log.json`
- verifier: `scripts/ci/check_tls_catalog_refresh.py`
- scheduled workflow: `.github/workflows/tls-catalog-refresh.yml`

Current policy:

- cadence: every 30 days or sooner when template families change
- enforcement: CI fails when the latest logged review is older than the cadence window
- scope: every bundled `tlsProfiles[]` entry must appear in the latest review log entry with the matching `catalogVersion`

## When To Change What

### Bump pack version only

Do this when:

- changing strategy notes or ordering
- changing pack membership
- changing rollout percentages or cohorts
- changing feature-flag defaults inside the same compatible schema

### Bump TLS catalog version

Do this when:

- changing the meaning of a TLS profile set
- changing allowed profile ids in a way that operators should be able to track separately
- rotating browser-fingerprint defaults that need explicit provenance

### Bump schema version

Do this only when:

- adding or removing required top-level catalog fields
- changing compatibility rules
- changing the meaning of existing serialized fields in a backward-incompatible way

Do not bump schema version for ordinary pack, rollout, or TLS profile refreshes.

## Anti-Rollback Rules

- Anti-rollback applies only to downloaded catalogs.
- The bundled asset remains the rollback-safe floor and does not need `sequence` monotonicity enforcement at load time.
- `sequence` is monotonic per channel and must increase with every published downloaded catalog.
- `issuedAt` must be an ISO-8601 UTC timestamp representing publication time for the signed catalog payload.
- The signed object is the catalog payload itself, so anti-rollback metadata belongs in the catalog, not the manifest.
- The debug override bypasses only the monotonic-sequence rejection. It does not bypass signature, checksum, compatibility, or freshness checks.

## Rollout Rules

- New transport behavior should be enabled through catalog feature flags before any app-default change.
- Keep risky transport changes staged through `rollout.percentage` and `rollout.cohort`.
- Prefer adding a new pack or TLS profile set over mutating a default in a way that hides provenance.
- Keep bundled fallback conservative enough to survive download or verification failure.

## Rollback Procedure

Use this order:

1. Disable the relevant feature flag in the remote catalog.
2. Reduce rollout percentage or move the affected pack out of the active cohort.
3. Publish a new catalog with a higher `sequence` and a fresh `issuedAt`, then regenerate checksum and signature.
4. Confirm compatible clients load the replacement snapshot.
5. If needed, rely on bundled fallback by allowing downloaded snapshots to age out or become incompatible.

If the downloaded snapshot is rejected, the app should continue to operate from the bundled asset.

## Maintainer Checklist

Before shipping a catalog update:

1. Keep `schemaVersion` at `3` unless compatibility truly changed.
2. Increment `sequence` above the last accepted downloaded catalog for that channel.
3. Set `issuedAt` to the publication time in ISO-8601 UTC form.
4. Verify `minAppVersion` and `minNativeVersion`.
5. Confirm every referenced `tlsProfileSetId`, `morphPolicyId`, `transportModuleId`, and `featureFlagId` exists.
6. Keep transport rollouts behind feature flags for non-trivial behavior changes.
7. Recompute SHA-256 for the final payload.
8. Sign the exact payload bytes that clients will download.
9. Publish the manifest with matching checksum, signature, algorithm, and key id.
10. Verify the bundled fallback still represents a safe baseline.
11. Update `docs/strategy-pack-tls-refresh-log.json` when the bundled TLS profile-set catalog changes or is re-reviewed.

## What Not To Do

- Do not use strategy packs to store secrets.
- Do not bypass signature verification for expedient rollouts.
- Do not publish an operational rollback by reusing or decrementing `sequence`; publish a new higher-sequence catalog instead.
- Do not hardcode new transport defaults in app code when a catalog flag can express the rollout.
- Do not bump schema version for routine content refreshes.
