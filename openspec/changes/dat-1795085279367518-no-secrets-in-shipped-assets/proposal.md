# Change: Keep secrets out of shipped app assets

Task ID: `DAT-1795085279367518`

## Why

The `simple` flavor asset `app/src/simple/assets/embedded-relay-bundle.json` ships WireGuard `private_key` and `preshared_key` values and a static proxy password inside every APK. Anyone who unpacks the APK holds working credentials for the fleet, and nothing in CI stops the next asset from doing the same. The existing one-time `/bootstrap/<token>` provisioning path can deliver credentials at first run instead.

## What Changes

- A build-time guard fails the build when any file under app asset directories contains a private key, preshared key, or static proxy password.
- Embedded relay credentials are obtained through the existing `/bootstrap/<one-time-token>` provisioning and stored in Keystore-backed storage; assets keep only non-secret endpoint metadata and a token slot.
- A rotation note lists every key and password that already shipped so that operators rotate them (documentation in the change, executed by the deploy side).
- This planning change does not touch the current untracked `app/src/simple/` file.

## Capabilities

### New Capabilities

- `shipped-asset-secrets`: no secret material in shipped assets; relay credentials arrive by bootstrap provisioning into Keystore-backed storage.

### Modified Capabilities

- None.

## Impact

- Build and CI: new `scripts/ci/check_shipped_asset_secrets.py`, wired in `build.gradle.kts` next to the existing `check_asn_exposure_denylist_assets.py` task, plus `scripts/tests/test_shipped_asset_secrets.py`.
- Runtime: `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/ProxyGroupStores.kt` (BOOTSTRAP kind), `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/subscription/SingBoxSubscriptionParser.kt`, Keystore precedent `core/service/src/main/kotlin/com/poyka/ripdpi/services/EncryptedSharedPreferencesCdnEchPersistedCache.kt`.
- Overlap: `preload-preset-remove-simple-flavor` (AND-1795015211660121, review) removes the compiled-in `embedded-relay-bundle.json` and adds a plaintext `app/src/main/assets/preset.json`. R1 narrows to the guard (covering `preset.json` and every other asset), bootstrap provisioning, and the rotation note; it does not remove the flavor.
- Related: `device-server-migration` (TRN-1792673614153980, review) for credential handling on migration.
- Counterpart `ripdpi-vpn-deploy` changes: D4 bundle v3 generation (bootstrap-issued credentials, key rotation); D1 contracts and D2-D3, D5-D7 are not required for R1.
- Parent: EPC-1795085162356362.
