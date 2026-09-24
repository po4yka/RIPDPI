---
name: protobuf-schema-evolution
description: Reserved-field rules and mechanical field-number lookup for evolving AppSettings, plus the Rust/Kotlin diagnostics wire contract. Use before assigning/removing a proto field or changing a wire message.
---

# Protobuf Schema Evolution

## Why This Matters

AppSettings is serialized as protobuf bytes and persisted on disk via Jetpack
DataStore. When the app updates, new code must read bytes written by every
older version. A field-number collision silently corrupts data -- the user
loses their configuration with no error and no recovery path.

The diagnostics engine adds a second dimension: Kotlin and Rust exchange JSON
wire messages whose schemas must stay in lockstep. A field mismatch causes
silent data loss or deserialization failures at runtime.

## AppSettings Proto

Schema: `core/data/model/src/main/proto/app_settings.proto`

Never trust a cached reserved-number list or highest-field-number in this skill, in a prior PR, or from memory -- both grow with every schema change and a stale number is exactly the silent-corruption risk this skill exists to prevent. Before assigning any new field number, run both of these against the live schema:

```bash
grep -n reserved core/data/model/src/main/proto/app_settings.proto
grep -oE '= [0-9]+;' core/data/model/src/main/proto/app_settings.proto | grep -oE '[0-9]+' | sort -n | tail -1
```

The first command prints every `reserved` block (there may be more than one, each with a numbers line and a names line); the second prints the highest field number currently declared anywhere in the file. Assign the next number strictly above that highest number, and confirm it does not fall inside any reserved range first.

## Rules

1. **Never reuse a field number.** Proto3 identifies fields by number. If
   field 15 was `desync_method` and you assign 15 to something new, existing
   persisted bytes silently decode as the wrong field.

2. **Reserve removed fields.** Add both the number AND name to `reserved`.
   This makes protoc reject future reuse at compile time.

3. **Use the next available number.** Do not fill gaps from past removals.

4. **Set defaults in AppSettingsSerializer.** Every new field needs a default
   in `AppSettingsSerializer.defaultValue` (`core/data/.../AppSettingsSerializer.kt`).
   Proto3 zero-values (0, false, "") are implicit; if the real default differs
   (e.g., `proxy_port = 1080`), the serializer is the only place it gets set.

5. **Validate string enums on read.** Proto stores enum-like fields as plain
   strings ("vpn", "proxy"). Fall back to a safe default on unrecognized values.

## Diagnostics Wire Contract

Rust (`ripdpi-diagnostics-contracts`, re-exported by `ripdpi-monitor-engine`) and Kotlin exchange JSON wire messages. Schema version
is tracked by a constant that must be equal on both sides:

- Rust: `DIAGNOSTICS_ENGINE_SCHEMA_VERSION` in `native/rust/crates/ripdpi-diagnostics-contracts/src/wire.rs`
- Kotlin: `DiagnosticsEngineSchemaVersion` in `core/diagnostics/.../contract/engine/EngineContract.kt`

`DiagnosticsContractGovernanceTest` enforces equality by parsing the Rust
source with `Regex("""DIAGNOSTICS_ENGINE_SCHEMA_VERSION:\s*u32\s*=\s*(\d+)""")`.

Wire types (all in `wire.rs` / `EngineContract.kt`):

| Rust | Kotlin | Direction |
|---|---|---|
| `EngineScanRequestWire` | `EngineScanRequestWire` | Kotlin -> Rust |
| `EngineScanReportWire` | `EngineScanReportWire` | Rust -> Kotlin |
| `EngineProgressWire` | `EngineProgressWire` | Rust -> Kotlin |

## Golden Contract Tests

Three test files enforce Kotlin/Rust wire compatibility using shared fixtures
in `diagnostics-contract-fixtures/`:

- **DiagnosticsWireContractTest** (Kotlin) -- asserts schema version and field
  manifests (`diagnostics_progress_fields.json`, `diagnostics_scan_report_fields.json`)
  match Kotlin wire classes.
- **DiagnosticsContractGovernanceTest** (Kotlin) -- decodes shared fixtures
  through Kotlin serializers, asserts schema version matches Rust, verifies
  bundled catalog matches committed fixture.
- **contract_fixtures.rs** (Rust) -- decodes the same fixtures through
  `serde_json`, asserts schema version, verifies outcome taxonomy covers all
  emitted native outcome tokens.

After a wire change: update structs in Rust/Kotlin, run Rust tests to
regenerate golden files (`assert_contract_fixture` writes on mismatch in
update mode), then run Kotlin tests. Commit updated fixtures with the code.

## Migration Patterns

### Adding an AppSettings field

1. Run the reserved/highest-number lookup above and pick the next available number. Never reuse a number cached in this skill, a past PR, or memory.
2. Add to `app_settings.proto`.
3. Set default in `AppSettingsSerializer.defaultValue`.
4. Map in UI state conversions (`toUiState()`, `toConfigDraft()`).
5. Existing users get proto3 zero-value on upgrade -- handle gracefully if
   the zero-value is not the desired default.

### Removing an AppSettings field

1. Remove field definition from `app_settings.proto`.
2. Add field number to `reserved` numbers list.
3. Add field name to `reserved` names list.
4. Remove all Kotlin references (serializer, UI, ViewModel).

### Adding a diagnostics wire field

1. Add field with `#[serde(default)]` in Rust wire struct.
2. Add defaulted field in Kotlin `@Serializable` data class.
3. Regenerate golden fixture JSONs.
4. If breaking (renamed/removed field, changed semantics), bump
   `DIAGNOSTICS_ENGINE_SCHEMA_VERSION` in both Rust and Kotlin.

## Checklist for Schema PRs

- [ ] No reused field numbers in `app_settings.proto`
- [ ] Removed fields added to both `reserved` lines (numbers AND names)
- [ ] Default set in `AppSettingsSerializer.defaultValue`
- [ ] Wire changes have `#[serde(default)]` / Kotlin defaults
- [ ] Schema version bumped if wire change is breaking
- [ ] Golden contract fixtures updated
- [ ] Both Rust (`cargo test --locked -p ripdpi-monitor-engine`) and Kotlin contract tests pass
