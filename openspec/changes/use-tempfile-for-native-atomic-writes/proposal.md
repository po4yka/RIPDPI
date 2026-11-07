# Change: Use tempfile for native atomic file replacement

Task ID: `RST-1794036651029797`

## Why

The three native state writers must share safe temporary-file lifecycle behavior without losing durability or file permissions. The 2026-11-07 audit identifies this as finding 13 at source revision 202f970.

## What Changes

- Use NamedTempFile::new_in in the destination directory, write and sync the file, persist it, then sync the parent directory as required by the existing contract.
- A successful replacement exposes a complete state file with the expected contents and permissions.
- A failed write or rename keeps the old state and cleans temporary files during normal error unwinding.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; Keep size limits, restrictive permissions and permission preservation, old-file retention on failure, and sync_all ordering. Drop cleanup is not a SIGKILL guarantee.

## Capabilities

### New Capabilities

- `standard-components/use-tempfile-for-native-atomic-writes`: The three native state writers must share safe temporary-file lifecycle behavior without losing durability or file permissions.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `native/rust/crates/ripdpi-runtime-policy/src/runtime_policy/autolearn/store.rs`
- `native/rust/crates/ripdpi-runtime-policy/src/runtime_policy/cache/connection.rs`
- `native/rust/crates/ripdpi-runtime-adaptive/src/adaptive_tuning/persistence/file_io.rs`

tempfile is already used for tests but will enter the production graph. Assess license, transitive dependencies, binary size, platform support, and approval required by repository policy before promotion. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
