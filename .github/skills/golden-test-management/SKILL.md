---
name: golden-test-management
description: Use when working with golden test fixtures, blessing goldens, adding new snapshot tests, debugging golden mismatches, or scrubbing volatile fields. Triggers on: golden test, bless golden, golden diff, test vector, snapshot test, RIPDPI_BLESS_GOLDENS, golden contract, golden mismatch.
---

# Golden Test Management

Golden contracts enforce compatibility for telemetry, diagnostics events, strategy-probe progress/report payloads, and exported data. Fixtures are **read-only by default** -- tests fail on unexpected diffs. Bless with `RIPDPI_BLESS_GOLDENS=1` to update.

Full documentation: `docs/testing.md` (Golden contracts section).

## Fixture Locations

| Layer | Location | Examples |
|-------|----------|----------|
| Rust | `native/rust/crates/{crate}/tests/golden/` | android-support, ripdpi-android, ripdpi-tunnel-android, ripdpi-monitor |
| JVM | `core/{module}/src/test/resources/golden/` | engine, service, diagnostics |
| Android instrumentation | `app/src/androidTest/assets/golden/` | Copies of JVM fixtures for on-device smoke tests |

## Environment Variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `RIPDPI_BLESS_GOLDENS` | Set to any value to write fixtures instead of comparing | Not set (read-only) |
| `RIPDPI_GOLDEN_ARTIFACT_DIR` | Override diff artifact output directory | `target/golden-diffs` (Rust), `build/golden-diffs/` (JVM) |

## Blessing Workflow

### Bless All Telemetry/Logging Goldens

```bash
bash scripts/tests/bless-telemetry-goldens.sh
```

This script:
1. Blesses Rust goldens (`android-support`, `ripdpi-android`, `ripdpi-tunnel-android`, `ripdpi-monitor`)
2. Blesses JVM goldens (`NativeTelemetryGoldenTest`, `ServiceTelemetryGoldenTest`)
3. Syncs instrumentation fixtures (copies JVM fixtures to `app/src/androidTest/assets/golden/`)

### Bless Specific Crate/Module

```bash
# Rust
RIPDPI_BLESS_GOLDENS=1 cargo test -p ripdpi-android --manifest-path native/rust/Cargo.toml

# Kotlin
RIPDPI_BLESS_GOLDENS=1 ./gradlew :core:engine:testDebugUnitTest --tests "*.NativeTelemetryGoldenTest"
```

### After Blessing

1. Review diffs: `git diff` -- verify changes are intentional
2. Commit with explanation of **why** the golden changed
3. If instrumentation fixtures were affected, verify the sync step ran

## Failure Artifacts

On mismatch, both Rust and JVM support libraries write three files:

| File | Content |
|------|---------|
| `{name}.expected` | The golden fixture content |
| `{name}.actual` | What the test produced |
| `{name}.diff` | Unified diff between expected and actual |

**Rust:** Written to `target/golden-diffs/` (or `RIPDPI_GOLDEN_ARTIFACT_DIR`).
**JVM:** Written to `{module}/build/golden-diffs/`.

## Support Libraries

### Rust: `golden-test-support` Crate

```rust
use golden_test_support::{assert_text_golden, canonicalize_json, canonicalize_json_with};

// Simple text comparison
assert_text_golden(env!("CARGO_MANIFEST_DIR"), "tests/golden/output.json", &actual);

// JSON with key sorting
let canonical = canonicalize_json(&json_string)?;
assert_text_golden(env!("CARGO_MANIFEST_DIR"), "tests/golden/data.json", &canonical);

// JSON with custom scrubbing
let canonical = canonicalize_json_with(&json_string, |value| {
    // Remove volatile fields like timestamps
    scrub_timestamps(value);
})?;
assert_text_golden(env!("CARGO_MANIFEST_DIR"), "tests/golden/data.json", &canonical);
```

### Kotlin: `GoldenContractSupport` Object

Located in `core/engine/src/test/kotlin/com/poyka/ripdpi/core/GoldenContractSupport.kt`:

```kotlin
// JSON comparison with canonical key ordering
GoldenContractSupport.assertJsonGolden(
    "snapshot.json",
    json.encodeToString(serializer, data),
)

// JSON with custom scrubbing
GoldenContractSupport.assertJsonGolden(
    "snapshot.json",
    json.encodeToString(serializer, data),
    ::scrubVolatileFields,  // (JsonElement) -> JsonElement
)

// Plain text comparison
GoldenContractSupport.assertTextGolden("output.txt", actualText)
```

## Scrubbing Volatile Fields

Fields that change between runs must be scrubbed for deterministic comparison:

**Scrubbed** (non-deterministic):
- Timestamps (`serviceStartedAt`, `lastFailureAt`, `updatedAt`, `capturedAt`, `createdAt`)
- Generated session IDs
- Loopback ports
- Absolute temp paths
- Archive-time dynamic file names

**Strict** (must match exactly):
- State and health values
- Counters
- Event order
- Log level and message text
- Route group and target metadata
- Strategy signatures and recommendations
- Per-lane TCP/QUIC/DNS metadata
- Strategy-probe progress lane/candidate metadata
- Audit assessment and target-selection metadata
- Resolver metadata and fallback state

### Kotlin Scrubbing Pattern

```kotlin
private fun scrubVolatileFields(value: JsonElement): JsonElement =
    when (value) {
        is JsonObject -> JsonObject(
            value.mapValues { (key, element) ->
                when (key) {
                    "serviceStartedAt", "lastFailureAt", "updatedAt",
                    "capturedAt", "createdAt" -> Json.parseToJsonElement("0")
                    else -> scrubVolatileFields(element)
                }
            },
        )
        else -> value
    }
```

## Adding a New Golden Test

### Rust

1. Add `golden-test-support` to `[dev-dependencies]` in the crate's `Cargo.toml`:
   ```toml
   [dev-dependencies]
   golden-test-support = { path = "../golden-test-support" }
   ```

2. Write the test:
   ```rust
   #[test]
   fn my_output_matches_golden() {
       let actual = produce_output();
       let canonical = canonicalize_json(&serde_json::to_string(&actual).unwrap()).unwrap();
       assert_text_golden(env!("CARGO_MANIFEST_DIR"), "tests/golden/my_output.json", &canonical);
   }
   ```

3. Create initial fixture: `RIPDPI_BLESS_GOLDENS=1 cargo test -p my-crate my_output_matches_golden`

4. Review and commit the new fixture file.

### Kotlin

1. Write the test using `GoldenContractSupport`:
   ```kotlin
   @Test
   fun myOutputMatchesGolden() {
       val actual = produceOutput()
       GoldenContractSupport.assertJsonGolden("my_output.json", jsonEncode(actual))
   }
   ```

2. Create initial fixture: `RIPDPI_BLESS_GOLDENS=1 ./gradlew :module:testDebugUnitTest --tests "*.MyGoldenTest"`

3. Review and commit the new fixture file.

### Instrumentation Sync

If the new golden is needed for Android instrumentation tests:
1. Add a `cp` line to `scripts/tests/bless-telemetry-goldens.sh`
2. Copy the JVM fixture to `app/src/androidTest/assets/golden/`

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Blessing without reviewing diffs | Always `git diff` after blessing. Unexpected changes may indicate bugs. |
| Forgetting to scrub volatile fields | New timestamp/ID fields cause non-deterministic failures. Add to scrub function. |
| Not explaining golden changes in commit | Golden updates should always explain why the expected output changed. |
| Missing instrumentation sync | JVM fixture updated but `app/src/androidTest/assets/golden/` not. Run bless script or add `cp` manually. |
| Adding golden without `canonicalize_json` | JSON key order is non-deterministic. Always canonicalize before comparing. |
| Hardcoding fixture path | Rust: use `env!("CARGO_MANIFEST_DIR")`. Kotlin: `GoldenContractSupport` resolves repo root automatically. |

## See Also

- `docs/testing.md` -- Full test stack documentation including golden contracts section
- `native/rust/crates/golden-test-support/src/lib.rs` -- Rust support library source
- `core/engine/src/test/kotlin/.../GoldenContractSupport.kt` -- Kotlin support library source
- `scripts/tests/bless-telemetry-goldens.sh` -- Blessing script
