# Support Settings Deep Links

Support settings deep links let support staff send a user a link that opens RIPDPI, previews a settings patch, and applies it only after the user confirms. The mechanism is local-only: the link carries the complete signed-off package payload, the app decodes it on device, previews every change, and writes to the existing `AppSettingsRepository` only after all operations validate.

## Supported links

RIPDPI accepts two support-config link forms:

- `ripdpi://support-config?payload=<base64url-json>`
- `https://po4yka.github.io/RIPDPI/support-config#<base64url-json>` or `https://po4yka.github.io/RIPDPI/support-config?payload=<base64url-json>`

The `payload` value is UTF-8 JSON encoded as unpadded URL-safe Base64. Encoded payloads larger than 24 KiB are rejected before decoding. Unsupported hosts, missing payloads, bad Base64, and malformed packages are rejected without changing settings.

## Package schema

The package schema is versioned independently from the protobuf settings schema. Current packages use `schema: 1`.

```json
{
  "schema": 1,
  "title": "Support configuration",
  "reason": "Apply support-recommended settings for this device.",
  "restartPolicy": "ask",
  "operations": [
    {
      "op": "set",
      "path": "settings.root_mode_enabled",
      "value": false,
      "sensitive_reason": "Disables rooted-device packet primitives for this support session."
    }
  ]
}
```

Fields:

- `schema`: must equal `1`.
- `title`: preview title shown to the user; the app falls back to a localized default when blank.
- `reason`: support explanation shown in the preview; the app falls back to a localized default when blank.
- `restartPolicy`: one of `ask`, `never`, or `required`; this is surfaced in the preview/apply result and does not restart services by itself.
- `operations`: non-empty list of operations. Current operation support is deliberately narrow: only `{"op":"set"}` is accepted.
- `path`: setting path under `settings.` using snake_case, kebab-case, or generated setter casing; the registry normalizes it to `settings.<snake_case>`.
- `value`: JSON value matching the target setting type.
- `sensitive_reason`: optional support-facing explanation for sensitive changes.

## Setting coverage

The registry is generated at runtime from `AppSettings.Builder` setters and repeated-field `addAll*` methods, excluding protobuf internals such as `setUnknownFields`. This means every top-level `AppSettings` field with a generated builder setter is addressable as `settings.<snake_case_field_name>`, and new settings become support-link addressable automatically when the protobuf builder exposes them.

Supported value shapes:

- Boolean, integer, long, double, and string settings use ordinary JSON primitives.
- Repeated string settings use JSON arrays of strings.
- `StrategyTcpStep`, `StrategyUdpStep`, and other protobuf-message settings use unpadded URL-safe Base64 of the serialized protobuf bytes.
- Repeated TCP/UDP strategy chains use arrays of those encoded protobuf-message strings.

Sensitive paths are flagged in the preview when they are explicitly listed or when the normalized path contains `token`, `credential`, `password`, `private_key`, or `keylog`. The explicit sensitive list currently includes `settings.root_mode_enabled`, `settings.proxy_allow_lan`, `settings.relay_dns_over_tunnel_enabled`, `settings.community_comparison_enabled`, `settings.detection_diagnostic_tls_keylog_path`, and `settings.ws_tunnel_allow_insecure_sni`.

## Apply semantics

Support settings are staged against a snapshot first. If any operation has an unsupported path, unsupported operation, or invalid value, the whole package is invalid and nothing is written. If every operation validates, preview returns the current value, next value, sensitivity, title, reason, and restart policy. Apply re-stages against the current snapshot and replaces the stored `AppSettings` only when the package is still valid.

This all-or-nothing behavior is load-bearing for support: a link must not partially update a device if a field name is stale, a value is malformed, or the installed app version does not support the intended setting.

## Ownership and tests

Owners:

- Link parser: `core/data/settings/src/main/kotlin/com/poyka/ripdpi/data/support/SupportSettingsDeepLinkParser.kt`
- Package codec: `core/data/settings/src/main/kotlin/com/poyka/ripdpi/data/support/SupportSettingsPackage.kt`
- Field registry: `core/data/settings/src/main/kotlin/com/poyka/ripdpi/data/support/SupportSettingsFieldRegistry.kt`
- Preview/apply use case: `core/data/settings/src/main/kotlin/com/poyka/ripdpi/data/support/SupportSettingsApplyUseCase.kt`
- App entry and preview UI: `app/src/main/kotlin/com/poyka/ripdpi/activities/MainActivity.kt`, `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/Route.kt`, and `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/support/`

Focused tests:

- `./gradlew :core:data:settings:testDebugUnitTest --tests 'com.poyka.ripdpi.data.support.*'`
- `./gradlew :app:testGithubDebugUnitTest --tests com.poyka.ripdpi.activities.MainActivityShellControllerTest --tests com.poyka.ripdpi.ui.navigation.RipDpiNavHostLogicTest`

The registry coverage test asserts that every generated top-level `AppSettings.Builder` setter has a support path. Keep that test as the guardrail when adding or removing settings.
