# DAT-1795085279367518: Keep secrets out of shipped app assets

## Objective

Builds fail on secrets in any app asset, and relay credentials arrive by bootstrap provisioning into Keystore-backed storage.

## Ownership

scripts/ci/check_shipped_asset_secrets.py, scripts/tests/test_shipped_asset_secrets.py, build.gradle.kts (serialized lane shared with the ASN guard and AND-1795015211660121), core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/ProxyGroupStores.kt and subscription/SingBoxSubscriptionParser.kt. Does not touch app/src/simple/.

## Execution

- [ ] DAT-1795085325555940 Add scripts/ci/check_shipped_asset_secrets.py scanning every asset directory by content #feature !crit @item:DAT-1795085279367518
- [ ] DAT-1795085326567906 Add scripts/tests/test_shipped_asset_secrets.py with rejecting and passing fixtures including preset.json #feature !crit @item:DAT-1795085279367518
- [ ] DAT-1795085327471398 Register the guard in build.gradle.kts beside the ASN denylist task and in just ci-gates #feature !crit @item:DAT-1795085279367518
- [ ] DAT-1795085328294027 Provision relay credentials via /bootstrap token into Keystore-backed storage with typed failure #feature !crit @item:DAT-1795085279367518
- [ ] DAT-1795085329212733 Write the rotation note of shipped key kinds and versions, without secret values #feature !crit @item:DAT-1795085279367518

## Verification

python3 -m unittest scripts/tests/test_shipped_asset_secrets.py; ./gradlew :core:data:testDebugUnitTest for provisioning; just ci-gates; ./taskctl validate.
