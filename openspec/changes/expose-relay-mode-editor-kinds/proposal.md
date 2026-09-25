# Change: Expose complete relay kinds in Mode Editor

Task ID: `RLY-1790340340749310`

## Why

Six supported relay kinds can be imported and saved, but Mode Editor cannot display their complete settings. Editing such a profile can therefore lose configuration or credentials.

## What Changes

- Add full create and edit controls for VLESS TLS/xHTTP, Trojan, Shadowsocks, Google Apps Script, Mieru, and SSH.
- Preserve unmodified imported fields and secrets when saving an existing profile.
- Reject incomplete or incompatible settings before saving.

## Capabilities

### New Capabilities

- `relay-mode-editor-kinds`: Complete editing of the six supported relay kinds.

### Modified Capabilities

- None.

## Impact

- Android app Compose editor, draft mapping, validation, relay profile persistence, and app tests. No protobuf, JNI, or Rust contract changes. No breaking changes.
