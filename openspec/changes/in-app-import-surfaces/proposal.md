# Change: Import from every in-app surface

Task ID: `AND-1792945974437056`

## Why

Commit 8f8e68274 added an Add subscription screen, an AmneziaWG `.conf` file import and wider QR scanning. A review found gaps that block common imports or leave unsafe state:

- A bare `https://…/bootstrap/<token>` link was enrolled as a long-lived subscription, which spends the one-time token.
- A first fetch that returned a web page saved a group that auto-update kept polling.
- AmneziaVPN `vpn://` keys (the client's default share format, also a multi-code QR series), plain WireGuard configs, the official `sing-box://` link and other clients' subscription links were rejected.
- Paste link, the share sheet and the QR scanner accepted different content. The scanner ignored a code after Back.
- Import errors did not tell an unreadable file from an invalid config, and extra peers were dropped silently.

## What Changes

- One `ImportPayloadClassifier` serves the QR scanner, Paste link and a new `ACTION_SEND` share-sheet path: proxy URIs (every codec scheme), subscription links, `.conf` text and AmneziaVPN keys.
- Subscription links: a `/bootstrap/` path keeps the one-time kind; `sing-box://…#name`, NekoBox `sn://subscription`, Clash, v2rayNG and Hiddify links parse; hosts with `_` are accepted.
- A new group whose first fetch is not a subscription (PARSE_ERROR or INVALID_PAYLOAD) is deleted. Transient failures still keep it for retry.
- `profile-title` names an auto-named group; `profile-update-interval` sets the refresh delay when the user did not set one.
- The AmneziaWG editor accepts plain WireGuard configs, reports extra peers, strips IPv6 endpoint brackets, and separates "could not read the file" from "not a valid config".
- Navigation replaces the scanner or Add screen, ignores a repeat while the target is shown, and keeps secrets out of navigation and saved state.

## Capabilities

### New Capabilities

- `in-app-import-surfaces`: what each surface accepts and how secrets are handed off.

### Modified Capabilities

- None.

## Impact

- App module only (`proxyimport`, `subscription`, scanner, AmneziaWG editor, navigation, full-flavor manifest), 10 locales, README files and import docs.
- No persisted schema, JNI, protobuf or native change. The AmneziaWG 3.x engine work is tracked separately.
