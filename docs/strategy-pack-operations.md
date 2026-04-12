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

## Verification Pipeline

The repository verifies downloaded catalogs in this order:

1. Download manifest.
2. Download catalog payload to a temporary file.
3. Compute SHA-256 while streaming to disk.
4. Compare the computed checksum to `catalogChecksumSha256`.
5. Verify `catalogSignatureBase64` using the trusted ECDSA public key.
6. Parse the catalog payload.
7. Reject it if app or native compatibility fails.
8. Cache the verified snapshot and expose it as `downloaded`.
9. Fall back to the bundled asset if the cached or downloaded snapshot is incompatible.

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

## Rollout Rules

- New transport behavior should be enabled through catalog feature flags before any app-default change.
- Keep risky transport changes staged through `rollout.percentage` and `rollout.cohort`.
- Prefer adding a new pack or TLS profile set over mutating a default in a way that hides provenance.
- Keep bundled fallback conservative enough to survive download or verification failure.

## Rollback Procedure

Use this order:

1. Disable the relevant feature flag in the remote catalog.
2. Reduce rollout percentage or move the affected pack out of the active cohort.
3. Publish a new manifest and catalog with updated checksum and signature.
4. Confirm compatible clients load the replacement snapshot.
5. If needed, rely on bundled fallback by allowing downloaded snapshots to age out or become incompatible.

If the downloaded snapshot is rejected, the app should continue to operate from the bundled asset.

## Maintainer Checklist

Before shipping a catalog update:

1. Keep `schemaVersion` at `3` unless compatibility truly changed.
2. Verify `minAppVersion` and `minNativeVersion`.
3. Confirm every referenced `tlsProfileSetId`, `morphPolicyId`, `transportModuleId`, and `featureFlagId` exists.
4. Keep transport rollouts behind feature flags for non-trivial behavior changes.
5. Recompute SHA-256 for the final payload.
6. Sign the exact payload bytes that clients will download.
7. Publish the manifest with matching checksum, signature, algorithm, and key id.
8. Verify the bundled fallback still represents a safe baseline.

## What Not To Do

- Do not use strategy packs to store secrets.
- Do not bypass signature verification for expedient rollouts.
- Do not hardcode new transport defaults in app code when a catalog flag can express the rollout.
- Do not bump schema version for routine content refreshes.
