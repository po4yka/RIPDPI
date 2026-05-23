# feature-contract-harness

Cross-layer feature-contract test harness for the five descriptor / section
platforms a typical RIPDPI feature has to touch:

| Family | Surface | Live registry |
|--------|---------|---------------|
| `proxy_setting` | proto field → Kotlin `*SettingsSection` → `AppSettingsSectionMapper` → wire JSON → Rust `RuntimeConfig` | (per-layer marker — no central registry) |
| `relay_kind` | proto comment → Kotlin `RelayKind*` constant → `RelayKindDescriptor` row → resolver registration → Rust `RELAY_TRANSPORT_REGISTRATIONS` | `ripdpi_relay_core::relay_transport_descriptor` |
| `strategy_step` | `StepType` variant → linkme registration → registry resolution | `ripdpi_strategy_registry::StrategyRegistry::step_descriptors` |
| `diagnostics_probe` | `Probe` impl → `PROBE_DESCRIPTORS` row → `SCHEDULED_PROBE_INVENTORY` row → monitor-engine `PROBE_STAGE_REGISTRATIONS` | `ripdpi_diagnostics_probes::descriptor_by_probe_type` |
| `root_helper_command` | `CMD_*` constant → params struct → `COMMAND_DESCRIPTORS` row → dispatch arm | `ripdpi_root_helper_protocol::command_descriptor` |

Goal: catch shotgun-surgery misses **at the test boundary** before a feature
PR lands, with failure messages that name the specific file the contributor
forgot.

## Authoring a new manifest

1. Pick a family directory under `manifests/`.
2. Add `<feature-name>.json` (kebab-case, must equal the manifest's `name`).
3. Set `wireId` to the canonical wire string the feature exposes (the
   string that the live registry indexes by). For `relay_kind` the only
   manifest whose `wireId` is not in `RELAY_TRANSPORT_REGISTRATIONS` is
   `off`, which is documented in the test as a passthrough exception.
4. List every layer the feature touches in `layers`. For each:
   - `id`: short snake_case label (`proto`, `kotlin_settings_section`,
     `rust_transport_descriptor`, …).
   - `path`: repo-root-relative path to the file.
   - `marker`: a substring the file MUST contain. Keep it narrow enough that
     unrelated edits cannot satisfy it accidentally, but stable enough not
     to break on a one-line rename.
   - `fixHint`: one sentence telling the contributor what to put back.
5. Fill the `checklist` with the human-readable shotgun-surgery list. Each
   line should mention an absolute path the contributor must edit.

## Schema

```jsonc
{
  "schemaVersion": 1,
  "family": "proxy_setting",          // one of KNOWN_FAMILIES
  "name": "proxy_listen_port",        // must equal the file stem
  "wireId": "port",                   // canonical wire string
  "summary": "…",
  "layers": [
    {
      "id": "proto",
      "path": "core/data/model/src/main/proto/app_settings.proto",
      "marker": "int32 proxy_port",
      "fixHint": "…"
    }
  ],
  "checklist": [
    "core/data/model/src/main/proto/app_settings.proto — add the proto field.",
    "…"
  ]
}
```

Field shape: see `src/lib.rs` (`FeatureManifest`, `ManifestLayer`).

Forward-compat: `serde_json` parses with the default tolerant mode; unknown
future fields are ignored. Bump `schemaVersion` only when an existing field
changes meaning.

## Running

```sh
cargo test --locked --manifest-path native/rust/Cargo.toml -p feature-contract-harness
```

Each family has its own integration test under `tests/`, plus
`manifest_self_check.rs` validating the manifest tree itself. A failure
message names the file + marker and prints the full checklist.

## Kotlin side

The same manifests power Kotlin contract tests for the two cross-language
families:

- `core/data/model/src/test/.../contract/ProxySettingFeatureContractTest.kt`
- `core/service/src/test/.../contract/RelayKindFeatureContractTest.kt`

Both walk up from the test's working directory to find `settings.gradle.kts`,
then read manifests under
`native/rust/crates/feature-contract-harness/manifests/`. JSON parsing is
inline via the project's `kotlinx.serialization` dependency.

## Constraints

- Test/harness only — no runtime code paths consume manifests.
- No network, root, or `.so`-loading requirements.
- Manifests are checked-in source: a PR that adds a new descriptor /
  registration MUST add or update a manifest in the same commit.
