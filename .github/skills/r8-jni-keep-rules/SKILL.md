---
name: r8-jni-keep-rules
description: >
  RIPDPI R8 / ProGuard keep-rule authoring guide. Use when adding a new `@Serializable`
  navigation route, a new JNI binding class, a new protobuf schema used by DataStore or
  the native config contract, or when a release build fails with a minification error and
  `missing_rules.txt` suggestions appear. Covers the three authoritative sources of truth
  (`core/engine/consumer-rules.pro`, `core/data/consumer-rules.pro`, `app/proguard-rules.pro`)
  and the policy for handling `missing_rules.txt` output symbolically rather than by verbatim
  copy-paste.
---

# R8 / JNI Keep-Rule Authoring

Release builds minify the app via R8. Three failure modes are common:

1. A native method loses its exact symbol name, and `System.loadLibrary` can't resolve the JNI signature → runtime `UnsatisfiedLinkError`.
2. A `kotlinx.serialization` `@Serializable` class loses its generated `$$serializer` or companion, and Navigation Compose type-safe route encode/decode fails → runtime `SerializationException` on nav transition.
3. A proto-lite class is renamed, and DataStore can't rehydrate persisted settings → runtime crash on first launch of the minified build.

The project keeps keep-rules narrow on purpose. Read the hard rule first:

> Do **not** paste `missing_rules.txt` suggestions verbatim. Add the **smallest** rule that names the exact compatibility boundary. No blanket `-keep class **` or `-dontwarn **` — the reviewer will push back.
> — `app/proguard-rules.pro:8-10`

## Where keep-rules live

| File | Scope | Contains |
|---|---|---|
| `core/engine/consumer-rules.pro` | JNI boundary for the native engine | `RipDpiProxyNativeBindings`, `Tun2SocksNativeBindings`, `NetworkDiagnosticsNativeBindings` — `-keepclasseswithmembernames` + `native <methods>;` only |
| `core/data/consumer-rules.pro` | Proto-lite compatibility surface | `com.poyka.ripdpi.proto.**` keep |
| `app/proguard-rules.pro` | App-level shims (intentionally tiny) | Only Guava j2objc `-dontwarn` today |

**Rule:** Engine/Data compatibility boundaries belong in **module-level** consumer rules, not in `app/proguard-rules.pro`. Reviewers reject app-level rules that could have lived in a consumer rule file.

## Decision tree

### Adding a new JNI binding class

When you add a class with `external fun` members called from Rust:

1. Add the class to `core/engine/consumer-rules.pro` following the exact pattern of the three existing entries.
2. Keep the rule to `-keepclasseswithmembernames` + `native <methods>;` only. Do **not** add `-keep class` (full class preservation) — only the native method names need to survive shrinking, the higher-level Kotlin wrapper does not.
3. Verify the native method signatures resolve post-shrink by running `./gradlew :app:assembleRelease` followed by launching the app and triggering a call into the new binding.

**Anchor example:**

```proguard
-keepclasseswithmembernames class com.poyka.ripdpi.core.RipDpiProxyNativeBindings {
    native <methods>;
}
```

### Adding a new `@Serializable` navigation route

Navigation Compose 2.9.7 type-safe routes (used in `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/RipDpiNavHost.kt:67-72` — `ConfigGraph`, `SettingsGraph`) encode/decode via `kotlinx.serialization` at runtime. R8 can strip the generated `$$serializer` companion.

**Current state:** the project has no app-level `-keep` rule for serializable routes today. It works because the routes are `data object`s (stateless) and the Compose/Kotlin serialization Gradle plugin emits a consumer rule. Verify this remains true when adding a `@Serializable data class` route with fields.

If a future release build crashes with `kotlinx.serialization.SerializationException: Serializer for class 'X' is not found`:

1. Add the **narrowest** rule that names the exact class + its serializer companion:
   ```proguard
   -keepclassmembers class com.poyka.ripdpi.ui.navigation.<RouteName> {
       kotlinx.serialization.KSerializer serializer(...);
       <fields>;
   }
   ```
2. Do **not** add `-keep class com.poyka.ripdpi.ui.navigation.** { *; }` — that defeats minification for the entire navigation graph.

### Adding a new proto-lite schema used at runtime

The existing rule `-keep class com.poyka.ripdpi.proto.** { *; }` covers everything under that package. If a new schema lands under a different package (e.g. a `core/diagnostics-data` proto), add a **module-scoped** consumer rule in that module's `consumer-rules.pro`, not a line in `app/proguard-rules.pro`.

### Handling `missing_rules.txt` after a failed release build

R8 emits `app/build/outputs/mapping/release/missing_rules.txt` with suggestions. These are **mechanical** — they don't know what's a genuine compatibility boundary vs. a false positive.

**Review protocol:**

1. Read each suggested rule symbolically — what class? what member? why would this survive minification?
2. If the rule is for a class that legitimately needs to survive (JNI symbol, serialization entrypoint, reflection), author the **narrowest** equivalent and add it to the correct module's `consumer-rules.pro`.
3. If the rule is for a class you control and that should have been obfuscation-safe, **fix the caller** (usually: remove reflection, remove `Class.forName` strings) rather than suppress.
4. Never commit `missing_rules.txt` as-is, and never `cat missing_rules.txt >> app/proguard-rules.pro`.

## Verification

After any change to a `.pro` file:

1. `./gradlew :app:assembleRelease` — must complete without R8 warnings escalating to errors.
2. Install the release APK on an emulator and exercise the code path the new rule protects (a nav transition for a route rule, an engine call for a JNI rule, a settings read for a proto rule).
3. `./gradlew staticAnalysis` — lint must not regress.
4. If `missing_rules.txt` appears with new suggestions after your change, treat the appearance itself as a review signal: something in your diff created a new boundary that R8 can't analyze. Surface this in PR review, don't paper over it.

## Out of scope

- Hilt, Compose, Room, Retrofit, Coroutines: rely on the generated `-consumerProguardFiles` from each library. Don't re-declare their rules.
- Manifest entry points (activities, services, broadcast receivers): keep their names automatically via `AndroidManifest.xml`.
- Guava: already handled by the single `-dontwarn com.google.j2objc.annotations.**` in `app/proguard-rules.pro`.

## External reference

Run `android skills r8` for Google's generic R8 skill; use it alongside this RIPDPI-specific skill for background on new R8 features, obfuscation dictionaries, or full-mode vs compat-mode semantics. This skill is the project-specific authority for *what* to keep and *where*; Google's skill explains *why* R8 behaves the way it does.
