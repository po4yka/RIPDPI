# Change: Replace custom hex codecs with standard library functions

Task ID: `DAT-1794036667822479`

## Why

Hex representations of existing fingerprints, digests, receipts, and configured keys must stay byte-for-byte identical with fewer codec copies. The 2026-11-07 audit identifies this as finding 15 at source revision 202f970.

## What Changes

- Use Kotlin ByteArray.toHexString and String.hexToByteArray, and existing Rust hex::decode_to_slice for the 32-byte shared-priors key.
- Each known byte vector produces exactly the previous lowercase hex string including leading zero bytes and existing prefixes.
- Malformed keys, wrong lengths, and explicitly all-zero shared-priors keys remain rejected with existing error categories.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; Keep lowercase, prefixes, lengths, case acceptance, malformed-input errors, unset-key semantics, and all-zero-key rejection. Do not change hash/MAC algorithms or stored identity values.

## Capabilities

### New Capabilities

- `standard-components/replace-custom-hex-codecs`: Hex representations of existing fingerprints, digests, receipts, and configured keys must stay byte-for-byte identical with fewer codec copies.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/FieldTelemetry.kt`
- `core/data/model/src/main/kotlin/com/poyka/ripdpi/data/NetworkStrategyMemory.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/NetworkSnapshotFactory.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ConnectionPolicySignatureBuilder.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/hosts/HostPackCatalogRepository.kt`
- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/subscription/DeviceMigrationContract.kt`
- `native/rust/crates/ripdpi-shared-priors/src/key_hex.rs`

Use only existing platform or workspace libraries. Check the direct module dependency before editing. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
