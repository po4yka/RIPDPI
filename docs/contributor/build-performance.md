# Build performance — local dev tuning

This page documents the build-performance knobs in `gradle.properties`,
`build-logic/`, and `.idea/`, and the per-user overrides under
`~/.gradle/gradle.properties`. Target hardware: 32 GB Mac (Apple Silicon
or Intel) running Android Studio Quail with the emulator.

The committed defaults aim for: fast Gradle sync, fast incremental
debug builds, and CI parity (CI behavior is unchanged).

For local builds on this Mac, apply the [machine gate and worker limits](#concurrency-ceiling)
before using the commands below. Gate admission, tool parallelism, and per-worktree
cache isolation are separate controls.

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

Copy the relevant settings from `gradle.properties.user.example` into
`gradle.properties` under the effective `GRADLE_USER_HOME` (normally
`~/.gradle`). The example file is documentation-only; Gradle does not read it directly.

Recommended opt-ins on a healthy 32 GB host:

```properties
# Gradle 9 pre-alpha. Big sync wins; turn off if AS sync complains about
# subproject isolation.
org.gradle.unsafe.isolated-projects=true
```

On this Mac, the user-owned Gradle init script under `~/.gradle/init.d/`
enforces a 5 GiB Gradle heap and 3 GiB Kotlin heap ceiling, even inside an
already-held gate. The committed 6 GiB Gradle default therefore needs this
local override; extra RAM does not authorize raising the machine limits:

```properties
org.gradle.jvmargs=-Xmx5g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:SoftRefLRUPolicyMSPerMB=50 -XX:MaxMetaspaceSize=1g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
kotlin.daemon.jvmargs=-Xmx3g -XX:+UseG1GC -Dfile.encoding=UTF-8
org.gradle.workers.max=4
ripdpi.nativeCpuBudget=4
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

## Worktree builds — cache & resource isolation

Keep build outputs and Gradle state scoped to each worktree; see the
[worktree workflow](../../AGENTS.md#worktree-and-commit-workflow).
Agents can inspect and edit concurrently. Heavy builds across all worktrees
share the machine-wide gate described below.

### sccache is the one cache safe to share across worktrees

Make local `RUSTC_WRAPPER=sccache` (above) your **default**, not an
opt-in, when you build from several worktrees. sccache is
content-addressed and concurrency-safe, so it deduplicates Rust compiles
**across** worktrees and branches — it is what turns the per-worktree
`native/rust/target/` rebuild (multi-GB, 4 ABIs) from a cold rebuild
into a cache hit.

Keep per-worktree `target/` directories so outputs and incremental state
remain isolated between checkouts; let sccache be the shared layer.
Target-directory isolation works alongside the machine-wide build gate.
(Cross-worktree hit-rate is high but not 100 % because
absolute paths differ per worktree — mozilla/sccache#2595.)

### Gradle build cache + daemon contention

To isolate Gradle caches and daemon state between worktrees, give each
worktree its own Gradle home:

```sh
# inside the worktree, before any ./gradlew invocation
export GRADLE_USER_HOME="$PWD/.gradle-home"
```

Each home re-warms and retains its own cache, so account for that disk cost
when creating worktrees. It also changes where Gradle reads user properties
and init scripts: install the local overrides there and load the machine's
existing guard with `--init-script "$HOME/.gradle/init.d/00-heavy-build-gate.gradle"`
when using an isolated home on this Mac. Keep that user-owned guard as the
source of truth rather than copying its body into the repository. Heavy builds
still use the top-level gate below.

### Concurrency ceiling

Run heavy local builds and compiler-backed tests across all worktrees through
the same machine-wide gate: `build-gate -- <command>`. This includes Gradle,
Cargo/Rust compilation, `xcodebuild`, CMake builds, Ninja, and parallel Make.
Inspect current slot capacity, holders, and queued commands with `build-gate --status`;
use `build-gate --help` for the current interface. A full gate makes `--status`
exit nonzero; wait for admission through the normal build command.

Acquire the gate once at the top level. Nested tools inherit `BUILD_GATE_HELD=1`
and reuse it; let the gate set that marker. Cargo wrappers can add automatic
guarding, but the explicit top-level command also covers different `PATH`
ordering and tools launched by Gradle.

The installed wrapper's defaults can exceed the repository's local build limits.
Keep Gradle workers and Cargo/CMake jobs at or below four; Rust release or LTO
compilation uses at most two jobs. Set tool limits explicitly: Gradle's
`--max-workers` does not bound child Cargo processes. The [native build policy](../../build-logic/convention/src/main/kotlin/NativeBuildPolicy.kt)
distributes `ripdpi.nativeCpuBudget` across ABI workers and passes an explicit
Cargo job count. Android native tasks reject ambient `CARGO_BUILD_JOBS`.
Use the Gradle property for Android builds and `--jobs` for direct Cargo builds.

```sh
# Local debug; use the per-user heap overrides described above.
CMAKE_BUILD_PARALLEL_LEVEL=4 \
  build-gate -- env -u CARGO_BUILD_JOBS ./gradlew :app:assembleDebug --max-workers=4 -Pripdpi.nativeCpuBudget=4

# Release/LTO: cap the combined native ABI budget at two.
CMAKE_BUILD_PARALLEL_LEVEL=2 \
  build-gate -- env -u CARGO_BUILD_JOBS ./gradlew :app:assembleRelease --max-workers=4 -Pripdpi.nativeCpuBudget=2
```

For direct Cargo commands, retain `--locked` whenever dependencies resolve and
set `--jobs` to the applicable limit. Use the repository's
[`cargo-guarded.sh`](../../scripts/ci/cargo-guarded.sh) for external subcommands
when the machine's Cargo wrapper requires it; it reuses an already-held gate.
Lightweight inspection such as `cargo metadata --locked`, `cargo fmt`, Gradle
`tasks`, or CMake configuration can run alongside gated work without acquiring
a slot. Compilation or tests triggered by an inspection command still need the gate.

### Device / emulator work is single-lane

`:app:ciDevicesGroupGithubFullDebugAndroidTest` managed devices and
`scripts/ci/run-android-journeys-emulator.sh` share one AVD and one adb
server. Run instrumented tests and journeys as a **serialised, single
lane** (or give each agent its own AVD/serial) — parallel runs collide
on the device. Host-side Rust/proxy tests bind ephemeral `:0` ports to avoid
network-port collisions; their compiler-backed commands still acquire the
machine-wide build gate.

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

```bash
set -o pipefail
unset CARGO_BUILD_JOBS
export CMAKE_BUILD_PARALLEL_LEVEL=2

# Property propagation + parallel CC active.
build-gate -- ./gradlew help --info --max-workers=4 -Pripdpi.nativeCpuBudget=2 \
  2>&1 | rg 'configuration cache|parallel'

# Single-ABI debug on Apple Silicon.
build-gate -- ./gradlew :app:assembleDebug --dry-run --info --max-workers=4 \
  -Pripdpi.nativeCpuBudget=2 2>&1 | rg 'Using default local native ABI'

# CI behavior unchanged.
CI=true build-gate -- ./gradlew :app:assembleDebug --dry-run --info --max-workers=4 \
  -Pripdpi.nativeCpuBudget=2

# Daemon heap.
./gradlew --status   # then `jps -v | rg GradleDaemon` for -Xmx

# Static analysis clean.
build-gate -- ./gradlew staticAnalysis --max-workers=4 -Pripdpi.nativeCpuBudget=2

# Locale parity across all resource XML files (including Hindi strings2.xml).
build-gate -- ./gradlew :app:lintGithubFullDebug :core:service:lintDebug \
  --max-workers=4 -Pripdpi.nativeCpuBudget=2
```

The CI-mode dry run checks configuration and task selection; verify all-ABI
selection against `resolvedNativeAbis()` in the native build policy and actual
build outputs. CI mode does not emit the local-default ABI log message.

## Things that intentionally did NOT change

- detekt / ktlint / lint / native-size / coverage baselines.
- AGP / Gradle / Kotlin / KSP / Compose Compiler / NDK / Rust toolchain
  versions.
- `[profile.android-jni]` (release) — fat LTO / opt-z / strip /
  codegen-units=1 are load-bearing for the native-size baseline.
- Roborazzi goldens (see `.claude/rules/golden-bless-discipline.md`).
- 9-locale strings parity contract.
- CI workflow YAML — propagation via `gradle.properties` is enough.
