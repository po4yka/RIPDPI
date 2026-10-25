## Context

Before this change, each surface had its own parser: the scanner used a scheme allowlist, Paste link used the proxy codec only, and there was no share-sheet text path. Subscription confirmation saved the group before the first fetch and never removed it.

## Decisions

- `ImportPayloadClassifier` tries, in order: proxy URI codec, `SubscriptionLinkClassifier`, `AmneziaVpnKeyDecoder`, then a `[Interface]` + `[Peer]` section check. The editor, not the classifier, validates a `.conf`, so a precise reason reaches the user.
- `vpn://` keys: base64url of Qt `qCompress` (4-byte big-endian size, then zlib). Decompression is capped at 256 KiB and must match the declared size. AmneziaVPN QR series chunks (magic 1984) are assembled in the scanner ViewModel. Source: amnezia-client `exportController.cpp`, `importController.cpp`, `qrCodeUtils.cpp`.
- Only PARSE_ERROR and INVALID_PAYLOAD on the first import of a new group delete it, through a Compensation mutation. The existing retry contract for transient failures stays.
- `profile-title` replaces only the host fallback name; `profile-update-interval` applies only when the stored delay is zero.
- The share sheet reads at most 64 KiB on the trampoline's main thread (documented shortcut).
- The subscription field is a visible secure text field, so keyboards do not learn the token.

## Risks / Trade-offs

- Any `https://` QR now offers "Add subscription"; the confirmation step and the discard rule bound the effect.
- Registering other clients' schemes at priority -100 keeps their own apps the default handler.

## Verification plan

Full `:app:testGithubFullDebugUnitTest`, app and service lint (locale parity), detekt, ktlint and architecture health. No device run is owned by this change.
