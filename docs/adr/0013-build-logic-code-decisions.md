# ADR 0013: Build logic code decisions

> Status: Accepted. Decision date: 2026-11-25.

## Context

These decisions moved out of source comments in `build-logic/` and `quality/detekt-rules/`. Each section names the code that the decision governs.

## Decisions

### build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts#sharedPriorsPublicKey

The ed25519 release key is an input of the cargo task, because `ripdpi-shared-priors` embeds it at compile time. A key change must run the task again. The task always sets `RIPDPI_SHARED_PRIORS_PUB_KEY`, also when the value is empty. A stale value in the Gradle daemon environment then cannot leak into the build. An empty value means that shared priors are not configured.

### build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts#prebuiltSourceDir

When a prebuilt directory is set, the task copies each artifact from `<prebuiltSourceDir>/<abi>/<outputName>` and does not run cargo. CI uses this after a per-ABI matrix prebuild in the debug build job. For pluggable transports, CI gives this directory only from the dedicated producer job. The manifest digest is a separate input. An artifact from another run then cannot replace the correct one after download.

### build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts#androidSdkCmake

The task resolves the Android SDK cmake binary on the Gradle task thread. Lazy property access (`sdkDir.get()`) is not safe from background threads. The cmake crate that boring-sys uses reads the `CMAKE` variable to select the binary. The Android SDK cmake has no macOS platform defaults, so it does not add `-isysroot MacOSX.sdk` when the build cross-compiles for Android. Homebrew cmake adds this flag.

### build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts#nativeConcurrency

The task builds all ABIs in parallel. Each ABI has its own `CARGO_TARGET_DIR`. The parallel work stays inside the configured native CPU budget. Cargo jobs control scheduling, so the task ignores `CARGO_BUILD_JOBS` and `CARGO_TARGET_DIR` from the ambient environment. The JNI tasks pass their own `--jobs`.

### build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts#staleBoringSslCmakeCache

The task deletes the BoringSSL cmake build when the cached cmake binary, C compiler, or C++ compiler no longer matches the toolchain. When cmake detects changed variables, it configures again. On macOS this adds `-arch arm64 -isysroot MacOSX.sdk` before the Android toolchain file takes effect, and the cross-compilation breaks. A new configure without `CMakeCache.txt` is correct, because the toolchain file applies from the first cmake call.

The task deletes the whole Cargo build-script run directory, not only `OUT_DIR`. The cmake cache is under `<run>/out/build`. If only `OUT_DIR` is deleted, the build-script output and fingerprint of Cargo stay, and the next link can fail on missing archives. When `<run>` is removed, the Cargo fingerprint becomes invalid, and boring-sys does not need to watch its mutable generated output.

### build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts#cargoEnvironment

A helper trusts only the asset bytes that its own Gradle build made. `RIPDPI_NFQWS2_TRUST` carries this trust.

`BORING_BSSL_RUST_CPPLIB` is `c++_static`. The shared libraries then do not depend on `libc++_shared.so` at run time, and the APK does not need to package it. Static linking is safe because each library loads on its own and the libraries do not share C++ STL state across JNI boundaries.

The task removes the Apple host variables for SDK and architecture from the process environment. CMake-based Rust dependencies then do not inherit macOS host settings when they cross-compile for Android. The task must remove them and not set them to empty values. With empty deployment targets, host rustc rejects the build.

### build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts#rustWorkspaceCrateSources

The Cargo task inputs include the shared schema file that the build script of `ripdpi-geo` reads.

### build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts#rustNativeLibsBuildDir

All native Cargo tasks share this local state. They build different output directories, but the packages of a shard have a large common Rust, C, and BoringSSL graph. One target root for each ABI lets Cargo reuse it across tasks.

### build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts#goBuild

Go needs the external Android linker for `android/arm` source builds. For `armeabi-v7a`, cgo is always on.

### build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts#skipNativeBuildWiring

The Rust build is wired into the JNI packaging tasks. A change that only touches Rust sources then cannot be skipped when the Android variants are otherwise up to date. With `-Pripdpi.skipNativeBuild=true`, CI jobs that need no native libraries (unit tests, Roborazzi) omit the wiring. The downstream Android tasks then do not pull in the long Rust build.

### build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts#generatedHelperExecutableJniLibsDir

The NaiveProxy upstream client binaries come from `native/naiveproxy-upstream/artifacts`. `scripts/ci/fetch-naive-upstream.py` fetches them. Gradle only checks the SHA-256 and never downloads.

### build-logic/convention/src/main/kotlin/ripdpi.android.native.gradle.kts#nativeLibrariesExtractedAtInstall

Helper executables (pluggable transports, NaiveProxy, Cloudflare helpers) ship as `lib<name>.so` and run from `ApplicationInfo.nativeLibraryDir`. Android 10 and later forbid `exec()` from app data directories. The package manager must extract native libraries at install time (`android:extractNativeLibs="true"`). Do not turn this off.

### build-logic/convention/src/main/kotlin/HelperExecutableJniLibsTask.kt#HelperExecutableJniLibsTask

The task stages each helper executable that the app process starts as `jniLibs/<abi>/lib<name>.so`. Android 10 and later (targetSdk 29 and higher) deny `exec()` of files in the app home directory (SELinux `execute_no_trans` on `app_data_file`). Helpers therefore cannot be copied out of assets and started. The package manager extracts jniLibs at install time into `nativeLibraryDir`, and an untrusted app can run files from there. ABI splits and bundles also filter jniLibs by ABI.

Direct helpers come from `<root>/<abi>/<name>`. Pluggable transports come from the output of the PT asset task. The task stages each upstream binary that `pluggable-transports.json` names, after it checks the recorded SHA-256. It publishes the manifest again as the only PT asset. The upstream NaiveProxy client comes from `<naiveUpstreamRoot>/<abi>/libnaive.so`, which `scripts/ci/fetch-naive-upstream.py` writes. The task checks its SHA-256 against `native/naiveproxy-upstream/sources.json` and never downloads.

### build-logic/convention/src/main/kotlin/HelperExecutableJniLibsTask.kt#helperExecutableJniLibName

The jniLibs file name must be the same as the name that the runtime resolves in `ApplicationInfo.nativeLibraryDir`. The runtime function is `helperExecutableLibraryFileName` in `:core:service`. The helper name of the upstream NaiveProxy client is `ripdpi-naive-chromium`. Its source file is `libnaive.so`.

### build-logic/convention/src/main/kotlin/NativeBuildPolicy.kt#defaultLocalAbis

The special token `host` derives the local default ABI from the JVM `os.arch`. Apple Silicon developers get `arm64-v8a`. Intel Mac and Linux x86_64 developers get `x86_64`. Nobody must commit a per-host override.

### build-logic/convention/src/main/kotlin/PreparePresetTask.kt#PreparePresetTask

The task turns a FULL backup export into the plaintext preset asset that the app restores on first launch. The preset carries no credentials. FULL is the only export type that keeps a subscription link. The task reads it, drops `privateData`, and writes `containsCredentials: false`. At least one group must hold a pending `BOOTSTRAP` subscription (the token slot that `PendingBootstrapProvisioner` consumes on first run). The task refuses profiles, group members, and any other subscription, and then it writes nothing. The error messages name group ids only, never a link, a token, or a profile field.

The envelope layout is the same as `BackupEncryption` in `:core:data`. Both sides decrypt the shared fixture `core/data/src/test/resources/backup/preset-fixture.ripdpibackup` in their tests (`PreparePresetTaskTest` and `BackupEncryptionTest`).

### build-logic/convention/src/main/kotlin/PreparePresetTask.kt#passphrase

`passphrase` is a test seam. The build reads the environment in the task action, so the value stays out of the configuration cache.

### build-logic/convention/src/main/kotlin/PreparePresetTask.kt#schemaSource

`BackupV1.kt` is the single owner of the supported schema version range. The task reads it as an input.

### build-logic/convention/src/main/kotlin/PreparePresetTask.kt#prepare

The task deletes the old preset file before it does any work. A failed run must not leave an older preset to be packaged.

### build-logic/convention/src/main/kotlin/RipDpiManagedDevices.kt#configureRipDpiManagedDevices

One Gradle Managed Device registry serves `:app` (through `ripdpi.android.application`) and `:baselineprofile` (through `ripdpi.android.test`). Instrumented tests and macrobenchmarks then run on the same declarative, version-pinned emulators, not on AVDs that a shell script provisions. The devices are Pixel 6 images:

- `pixel6Api27Aosp`: minSdk compatibility smoke.
- `pixel6Api33Google`: runtime and software IME smoke.
- `pixel6Api34Atd`: the pinned macrobenchmark device (aosp-atd image).
- `pixel6Api35Google`: previous-target compatibility smoke.
- `pixel6Api36Google`: Android 16 runtime smoke.
- `pixel6Api37Google`: targetSdk compatibility smoke.
- `ciDevices`: the group for API 27, 33, 35, 36, and 37.

Gradle generates one task for each device, for example `:app:pixel6Api27AospGithubDebugAndroidTest`, and `:app:ciDevicesGroup<Variant>AndroidTest` for the group.

### build-logic/convention/src/test/kotlin/RipDpiManagedDevicesTest.kt#ciInstrumentedDevices

The `change-routing` job in `ci.yml` emits the emulator matrix as a JSON list. Pull requests run the `pull_request` subset. The test reads that list from the workflow file.

### build-logic/convention/src/main/kotlin/RipDpiRobolectric.kt#configureRipDpiRobolectric

Each test fork is a Robolectric JVM. `maxParallelForks` is half of the cores, so Gradle, the compiler, and the other forks stay responsive.

### build-logic/convention/src/main/kotlin/RobolectricNativeResources.kt#configureRobolectricNativeResources

The transform keeps the original native JAR and puts its unchanged font data before it on the JVM test classpaths. AGP applies the `unitTests.all` callback after it sets the original unit-test classpath, so the new entries stay first.

### build-logic/convention/src/main/kotlin/RoomSchemaArgProvider.kt#RoomSchemaArgProvider

The provider gives Room its schema directory as a task input with a relative path. A plain `arg()` string puts the absolute checkout path in the KSP cache key, and then no other checkout can reuse the output.

### build-logic/convention/src/main/kotlin/com/poyka/ripdpi/buildlogic/SharedPriorsKey.kt#SharedPriorsKey

One resolver gives the shared-priors release public key to both the cargo environment (`ripdpi.android.rust-native`) and the `:core:service` BuildConfig flag. Native code and Kotlin then always agree. The sources are, first non-blank value wins: the Gradle property `sharedPriors.publicKey`, `local.properties`, and the environment variable `RIPDPI_SHARED_PRIORS_PUB_KEY`. A blank value counts as absent. A non-blank value must be 64 hex characters and not all zero, or the build fails. The provider gives an empty string when no key is configured.

### build-logic/convention/src/main/kotlin/ripdpi.android.detekt.gradle.kts#detekt

Baselines are permanently off. The code must fix all violations.

### build-logic/convention/src/main/kotlin/ripdpi.android.hilt.gradle.kts#dependencies

The Kotlin metadata library that Hilt bundles is older than new Kotlin releases and fails with "Provided Metadata instance has version X, while maximum supported version is Y". Dagger 2.57 and later do not shade it. The build overrides it with the `kotlin-metadata-jvm` version that matches the Kotlin compiler.

### build-logic/convention/src/main/kotlin/ripdpi.android.lint.gradle.kts#lint

The CI GitHub release shard passes `ripdpi.lintReleaseBuilds=false`, because the `gradle-static-analysis` job already lints those sources. Every other build keeps `lintVital`. Pull requests pass `ripdpi.lintTestSources=false` and skip test sources. Pushes to `main` lint them.

### build-logic/convention/src/main/kotlin/DefaultDiagnosticsCatalogPackSource.kt#neutral-control

Each control carries the in-path transfer check, which needs a small complete body. Controls come from independent providers. If one provider is blocked (Cloudflare in Russia), a baseline stays.

### build-logic/convention/src/main/kotlin/DefaultDiagnosticsCatalogPackSource.kt#domestic-control

This pack is a raw-path baseline for the local network. If these targets answer while every foreign control fails, the international link is the problem. It has two providers, so one outage does not look like a broken network.

### build-logic/convention/src/main/kotlin/DiagnosticsCatalogDomain.kt#CatalogControlTier

The tier tells where a control lives. One blocked provider or a broken international link must not look like a broken network.

### build-logic/convention/src/main/kotlin/DiagnosticsCatalogRendering.kt#validate

A strategy profile must not use a domestic control. A domestic site answers without any bypass, so it cannot prove a strategy or an in-path route. `DiagnosticsCatalogStrategyControlTest` checks the same rule, and it also checks that:

- a control path is not a site root, because the in-path transfer reads at most 16 KiB and a root page can never verify the active path;
- at least one control is not on `cloudflare.com`, because Cloudflare is blocked in some regions and cannot be the only baseline.

### build-logic/convention/src/main/kotlin/DiagnosticsCatalogHttp3.kt#Http3ProbeDefinition

The default control host is reachable in Russia, where Cloudflare is blocked. `DiagnosticsCatalogPmtu.kt#PmtuProbeDefinition` uses the same default for the same reason.

### build-logic/convention/src/main/kotlin/DiagnosticsCatalogSelectiveMatrix.kt#selectiveMatrixTarget

The announcement covers services, not every hostname or future operator policy. Other cohorts describe provider origin. They do not claim exclusion from an allowlist. Their source date is the date of the endpoint observation, not its publication date. `lastVerifiedAt` records that HTTPS checks returned complete 200 responses from the maintainer workstation on that date. It is catalog maintenance evidence and not a guarantee of current health.

### build-logic/convention/src/main/kotlin/DiagnosticsCatalogSharedData.kt#tcpTlsControls

Both providers publish the HTTPS hostname for these resolver addresses.

### build-logic/convention/src/main/kotlin/DiagnosticsCatalogSharedData.kt#telegramPrimaryDcEndpoints

The addresses are the primary production IPv4 addresses from TDLib commit `691cb6a775d25d79ef1aea4e9fdd334f12a607be`, file `td/telegram/net/ConnectionCreator.cpp`. Coverage stays bounded to two explicit TCP ports.

### quality/detekt-rules/src/main/kotlin/com/poyka/ripdpi/quality/detekt/NoResolverIpInLogs.kt#NoResolverIpInLogs

The rule stops logging of DNS resolver or upstream endpoint IP addresses. Such addresses leak user-visible network state into logs that third parties can read (crash reporters, adb, bug reports). Code must use a non-identifying placeholder, for example `Logger.v { "DNS configured (${resolvers.size} resolvers)" }`. The text `Logger.v { "DNS: $dns" }` is not allowed.

### quality/detekt-rules/src/main/kotlin/com/poyka/ripdpi/quality/detekt/NoResolverIpInLogs.kt#isLogCall

For `Logger.v { ... }`, the PSI is a `KtDotQualifiedExpression` with the receiver `KtNameReferenceExpression("Logger")` and a `KtCallExpression` whose callee is `v`. The callee text is `v` and not `Logger.v`. The rule must go up to the parent dot-qualified expression to get the receiver.
