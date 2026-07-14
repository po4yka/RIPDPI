# Build performance — local dev tuning

This page documents the build-performance knobs in `gradle.properties`,
`build-logic/`, and `.idea/`, and the per-user overrides under
`~/.gradle/gradle.properties`. Target hardware: 32 GB Mac (Apple Silicon
or Intel) running Android Studio Quail with the emulator.

The committed defaults aim for: fast Gradle sync, fast incremental
debug builds, and CI parity (CI behavior is unchanged).

## Committed defaults (`gradle.properties`)

| Setting | Value | What it does |
| --- | --- | --- |
| `org.gradle.jvmargs` | `-Xmx6g` + G1, MaxGCPauseMillis=200, MaxMetaspace=1g | Gradle daemon heap, sized for KSP2 + 16-module Compose graph on 32 GB hosts. |
| `org.gradle.parallel` | `true` | Run independent project tasks in parallel. |
| `org.gradle.caching` | `true` | Local build cache. |
| `org.gradle.configuration-cache` | `true` | Skip re-running configuration when inputs are unchanged. |
| `org.gradle.configuration-cache.parallel` | `true` | Gradle 9 feature: parallel CC store/load. |
| `org.gradle.vfs.watch` | `true` | File-system watching, skips full source scans. |
| `org.gradle.welcome` / `warning.mode` | `never` / `summary` | Quieter logs; surface warnings via `--warning-mode=all` when investigating. |
| `kotlin.daemon.jvmargs` | `-Xmx3g` + G1 | KSP2 needs more than the 1.5 GB default. |
| `kotlin.incremental` | `true` | Explicit (matches Kotlin default). |
| `ksp.useKSP2` | `true` | Pin KSP2 (default in KSP 2.3.x; prevents silent regression). |
| `android.nonTransitiveRClass` | `true` | Smaller library R classes. |
| `android.nonFinalResIds` | `true` | AGP perf win for library modules; no behavior change. |
| `ripdpi.localNativeAbisDefault` | `host` | Local debug builds the host-matching ABI only. CI/release: all 4. |

`.idea/` is gitignored, so IDE-side heap tuning is per-user (see
"Android Studio" below).

## Native build — host-matching single ABI for local debug

`build-logic/convention/src/main/kotlin/NativeBuildPolicy.kt::resolvedNativeAbis()`
maps the local default:

- `ripdpi.localNativeAbisDefault=host` (committed default)
  - `os.arch=aarch64` (Apple Silicon, ARM Linux) → `arm64-v8a`
  - `os.arch=x86_64`/`amd64` (Intel Mac, Intel Linux) → `x86_64`
- CI (`CI` env var set) or any release-like task (`*Release`, `*Bundle`,
  `*Publish`) → unchanged `ripdpi.nativeAbis` (all 4 ABIs).
- `ripdpi.localNativeAbis=...` in `~/.gradle/gradle.properties` or
  `-Pripdpi.localNativeAbis=...` always wins.

The Gradle log line `Using default local native ABI set for non-release
build: arm64-v8a (host=aarch64)` shows the choice on every run.

To plug in an x86_64 system image on an Apple Silicon Mac (or vice
versa), add to `~/.gradle/gradle.properties`:

```properties
ripdpi.localNativeAbis=arm64-v8a,x86_64
```

## Per-user overrides

Copy any subset of `gradle.properties.user.example` into your
`~/.gradle/gradle.properties`. The file is documentation-only; Gradle
does not read it directly.

Recommended opt-ins on a healthy 32 GB host:

```properties
# Gradle 9 pre-alpha. Big sync wins; turn off if AS sync complains about
# subproject isolation.
org.gradle.unsafe.isolated-projects=true
```

If you have 64 GB+ headroom, bump heaps:

```properties
org.gradle.jvmargs=-Xmx10g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:SoftRefLRUPolicyMSPerMB=50 -XX:MaxMetaspaceSize=1g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
kotlin.daemon.jvmargs=-Xmx5g -XX:+UseG1GC -Dfile.encoding=UTF-8
```

## Local Rust sccache

CI already wires `RUSTC_WRAPPER=sccache`. Local dev does not.

```sh
brew install sccache
# in ~/.zshrc or ~/.zprofile:
export RUSTC_WRAPPER=sccache
export SCCACHE_DIR="$HOME/.cache/sccache"
export SCCACHE_CACHE_SIZE=20G
```

`aws-lc-sys` and `boring-sys` C compilation is not wrapped — same
limitation as CI (`scripts/ci/run-rust-native-checks.sh`).

Check cache hit rate any time with `sccache --show-stats`.

## Parallel agent builds — cache & resource isolation

When you run more than one git worktree at once (the standard flow for
parallel agents and dynamic `workflow` / `/goal` runs — see AGENTS.md §
Git Worktree & Commit Workflow), the committed single-checkout defaults
above become contended. Apply these before launching ≥2 worktrees that
build.

### sccache is the one cache safe to share across worktrees

Make local `RUSTC_WRAPPER=sccache` (above) your **default**, not an
opt-in, the moment you run parallel worktrees. sccache is
content-addressed and concurrency-safe, so it deduplicates Rust compiles
**across** worktrees and branches — it is what turns the per-worktree
`native/rust/target/` rebuild (multi-GB, 4 ABIs) from a cold rebuild
into a cache hit.

Do **not** point every worktree at a single `CARGO_TARGET_DIR`. Cargo
locks the target directory, so a shared one *serialises* parallel builds
and defeats the isolation. Keep per-worktree `target/`; let sccache be
the shared layer. (Cross-worktree hit-rate is high but not 100 % because
absolute paths differ per worktree — mozilla/sccache#2595.)

### Gradle build cache + daemon contention

`org.gradle.caching=true` points at the shared `~/.gradle/caches`. Two
worktrees building Android in parallel can deadlock it with
`LockTimeoutException` (gradle#8375), and each worktree spawns its own
6 GB daemon (`org.gradle.jvmargs`), so two daemons can OOM a 32 GB host.
For the worktree(s) that build Android in parallel, give each its own
Gradle home:

```sh
# inside the worktree, before any ./gradlew invocation
export GRADLE_USER_HOME="$PWD/.gradle-home"
```

This isolates the build cache and daemon per worktree. Disk cost is real
(each home re-warms its cache), which is why the next rule caps how many
run at once.

### Concurrency ceiling

Cap **full Android / 4-ABI native builds to ~2 concurrent worktrees** on
a 32 GB host — the daemon-heap budget above does not stretch further.
Read-only subagent fan-out (search, audit, review — no build) has no
ceiling; it needs neither a worktree nor a Gradle home.

### Device / emulator work is single-lane

`:app:ciDevicesGroupGithubFullDebugAndroidTest` managed devices and
`scripts/ci/run-android-journeys-emulator.sh` share one AVD and one adb
server. Run instrumented tests and journeys as a **serialised, single
lane** (or give each agent its own AVD/serial) — parallel runs collide
on the device. Host-side Rust/proxy tests already bind ephemeral `:0`
ports and are safe to parallelise.

### Disk hygiene

Per-worktree `target/` + per-worktree `GRADLE_USER_HOME` add up fast
across a `/goal` run. After integrating (and only after the user
confirms removal — AGENTS.md § Git Worktree & Commit Workflow), reclaim
space:

```sh
git worktree list                       # see what is live
git worktree remove .claude/worktrees/<slug>
git worktree prune                      # drop stale metadata
```

## Android Studio (per-user — not committed)

`Help → Edit Custom VM Options`:

```
-Xmx3g
-XX:ReservedCodeCacheSize=512m
-XX:+UseG1GC
```

`Settings → Build → Compiler → "Build process heap size"`: 3072 MB.

Both settings write to per-user paths that are not in source control
(`.idea/` is gitignored).

Project JDK stays at `jbr-21`; language level stays at `JDK_17`.

## Verification

After changing any committed knob:

```sh
# Property propagation + parallel CC active.
./gradlew help --info 2>&1 | grep -E 'configuration cache|parallel'

# Single-ABI debug on Apple Silicon.
./gradlew :app:assembleDebug --dry-run --info 2>&1 \
  | grep 'Using default local native ABI'

# CI behavior unchanged.
CI=true ./gradlew :app:assembleDebug --dry-run --info 2>&1 \
  | grep -i 'native ABI'

# Daemon heap.
./gradlew --status   # then `jps -v | grep GradleDaemon` for -Xmx

# Static analysis clean.
./gradlew staticAnalysis

# Locale parity across all resource XML files (including Hindi strings2.xml).
./gradlew :app:lintGithubFullDebug :core:service:lintDebug
```

## Things that intentionally did NOT change

- detekt / ktlint / lint / native-size / coverage baselines.
- AGP / Gradle / Kotlin / KSP / Compose Compiler / NDK / Rust toolchain
  versions.
- `[profile.android-jni]` (release) — fat LTO / opt-z / strip /
  codegen-units=1 are load-bearing for the native-size baseline.
- Roborazzi goldens (see `.claude/rules/golden-bless-discipline.md`).
- 9-locale strings parity contract.
- CI workflow YAML — propagation via `gradle.properties` is enough.
