# ripdpi-cli

## Status

This is a **local-only debug binary**. It is not shipped in the Android APK,
not used by the production runtime, and not published to crates.io. It exists
solely for development and debugging on the host machine.

Do not depend on this crate from any production crate. It must remain listed
under `local-debug-crates` in `[workspace.metadata.ripdpi]`.

## Usage

```
cargo run -p ripdpi-cli -- --help
```

All supported flags and sub-commands are documented via `--help`.
