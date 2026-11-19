## Context

`app/src/simple/assets/embedded-relay-bundle.json` is untracked and owned by someone else; this change never reads or edits it. Existing asset guards live in `build.gradle.kts` (the ASN denylist task invoking `scripts/ci/check_asn_exposure_denylist_assets.py`). The bootstrap link kind is documented in `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/ProxyGroupStores.kt`.

## Goals / Non-Goals

- Goal: no secret can ship in an asset; credentials arrive at runtime into Keystore-backed storage.
- Non-goal: removing the simple flavor (owned by AND-1795015211660121).
- Non-goal: rotating keys on servers (deploy side D4).

## Decisions

- Scan by content, not filename: key names `private_key`, `preshared_key`, `password`, `psk`, PEM private-key headers, and base64 32-byte values under those keys. Rejected: file-name allow-list, which a rename defeats.
- Use Android Keystore-backed `EncryptedSharedPreferences` or the project's existing credential store, following `EncryptedSharedPreferencesCdnEchPersistedCache.kt`. Rejected: a new crypto layer.
- Assumption: `/bootstrap/<token>` already returns the credential fields the relay profile needs; if not, deploy D4 must add them and this change records the gap.
- Assumption: `preset.json` from AND-1795015211660121 will carry no credentials; if it does, that change must be narrowed first.

## Contracts and ownership

- New script and its Python test; `build.gradle.kts` task registration (serialized: shared with the ASN guard and the simple-flavor removal change).
- Provisioning code in `core/data/runtime-state` and the service layer; no protobuf change expected.

## Risks / Trade-offs

- False positives on test fixtures -> the guard scans only app asset directories, not test resources.
- Keys already shipped remain valid until rotated -> rotation note plus D4 rotation.

## Migration Plan

Land the guard first (it fails on the current simple asset, which is the point; the owner of that file must remove secrets or the flavor change must land). Then provisioning. BREAKING: bundled credentials stop working; first run needs a bootstrap token. Rollback is reverting the change; shipped keys stay compromised regardless.
