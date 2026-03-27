---
name: protobuf-datastore
description: Use when modifying app settings schema, adding new preferences, working with DataStore persistence, or converting between proto messages and Kotlin types
---

# Protobuf DataStore

## Overview

RIPDPI persists all app settings as a single Protobuf message (`AppSettings`) stored via Jetpack DataStore. The schema is the source of truth for all configurable values.

## Key Files

| File | Purpose |
|------|---------|
| `core/data/src/main/proto/app_settings.proto` | Schema definition for persisted app settings |
| `core/data/.../data/AppSettingsSerializer.kt` | Serializer with default values |
| `core/data/.../data/AppDataStore.kt` | DataStore extension property |
| `core/engine/.../core/RipDpiProxyPreferences.kt` | Proto -> native preferences conversion |
| `app/.../activities/SettingsViewModel.kt` | Proto -> UI state conversion |
| `app/.../activities/ConfigViewModel.kt` | Config draft validation and persistence |

## Adding a New Setting

1. **Add field to proto schema** (`app_settings.proto`):
   ```protobuf
   bool your_new_setting = <next_field_number>;
   ```
   Use next available field number. Never reuse or renumber existing fields.

2. **Set default in serializer** (`AppSettingsSerializer.kt`):
   ```kotlin
   override val defaultValue: AppSettings = AppSettings.newBuilder()
       // ... existing defaults ...
       .setYourNewSetting(false)
       .build()
   ```

3. **Expose in UI state** (`SettingsUiState` or `ConfigDraft`):
   ```kotlin
   data class SettingsUiState(
       // ... existing fields ...
       val yourNewSetting: Boolean = false,
   )
   ```

4. **Map in conversion function** (`AppSettings.toUiState()` or `toConfigDraft()`):
   ```kotlin
   fun AppSettings.toUiState() = SettingsUiState(
       // ... existing mappings ...
       yourNewSetting = this.yourNewSetting,
   )
   ```

5. **Persist changes** via DataStore:
   ```kotlin
   context.settingsStore.updateData { current ->
       current.toBuilder().setYourNewSetting(value).build()
   }
   ```

## Reading Settings

```kotlin
// Reactive (preferred): Flow<AppSettings>
context.settingsStore.data.collect { settings ->
    val value = settings.yourSetting
}

// In ViewModel: combine with other flows
val uiState = combine(application.settingsStore.data, otherFlow) { settings, other ->
    UiState(setting = settings.yourSetting, /* ... */)
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())
```

## Proto Field Groups

| Group | Fields | Notes |
|-------|--------|-------|
| General | app_theme, ripdpi_mode, dns_ip, ipv6_enable | Mode is "vpn" or "proxy" |
| Command mode | enable_cmd_settings, cmd_args | Raw CLI args for command-line mode of the native RIPDPI proxy |
| Proxy | proxy_ip, proxy_port, max_connections, buffer_size | Defaults: 127.0.0.1:1080 |
| Desync | desync_method, split_position, fake_ttl, fake_sni, etc. | Method: none/split/disorder/fake/oob/disoob |
| Protocols | desync_http, desync_https, desync_udp | Bool flags for which protocols to desync |
| Hosts | hosts_mode, hosts_blacklist, hosts_whitelist | Mode: disable/blacklist/whitelist |
| TLS | tlsrec_enabled, tlsrec_position, tlsrec_at_sni | TLS record splitting |
| App | onboarding_complete, biometric_enabled, backup_pin | App lifecycle settings |

## Preferences Conversion (Proto -> Native)

`RipDpiProxyPreferences.fromSettingsStore(context)` reads the proto and creates either:
- `RipDpiProxyCmdPreferences(args)` if `enable_cmd_settings` is true
- `RipDpiProxyUIPreferences(...)` mapping all 27 proxy parameters

## Diagnostics Interaction

- `enable_cmd_settings = true` is a hard stop for automatic probing/audit. Those workflows require UI-config JSON so they can launch isolated strategy trials.
- If a change touches diagnostics availability or recommendation flow, also inspect `core:diagnostics` and the diagnostics UI layer. The proto flag alone is not the whole behavior.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Reusing proto field numbers | Always use next available number; old numbers are reserved forever |
| Forgetting default in serializer | Every field needs an explicit default in `AppSettingsSerializer.defaultValue` |
| Reading DataStore on main thread | Always use `Flow.collect` or `updateData` (both suspend) |
| Not updating `toUiState()` mapping | New fields must be mapped in all conversion functions |
| String enums without validation | Proto stores strings ("vpn", "proxy") -- validate on read |
