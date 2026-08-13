# RLY-1786618247484998: Add safe imported profile preflight

## Objective

Add a localized `Check profile` action that runs one isolated, bounded TCP relay preflight before import, never mutates durable or active configuration, yields truthful privacy-safe state, and leaves no runtime resources behind.

## Ownership

- App projection and activation: `app/src/main/kotlin/com/poyka/ripdpi/proxyimport/**` and focused tests under `app/src/test/**/proxyimport/**`.
- Service orchestration and lifecycle: `core/service/src/main/kotlin/com/poyka/ripdpi/services/**`, VPN/proxy start integration, and focused `core/service/src/test/**` coverage.
- Import UI: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/proxyimport/**`, its ViewModel/Compose tests, and `ProfileImportConfirmScreenshotTest`.
- Serialized locale lane: the nine `app/src/main/res/values*/strings*.xml` sets have one writer.
- Serialized golden lane: only the profile-import Roborazzi fixture, and only after explicit fixture-family authorization and semantic diff review.
- Out of scope: Rust crates, native/JNI wire contracts, protobuf, Room, DataStore, dependency manifests, relay registries, and unrelated task artifacts.

## Execution

- [x] RLY-1786618555627276 In a one-test RED/GREEN cycle, extract a pure `RelayProfileProjection` shared by `RelayProfileActivator` and preflight input, preserve exact activation records and credentials for every supported profile kind, and prove unsupported kinds remain fail-closed without writes #feature !high @item:RLY-1786618247484998
- [x] SVC-1786618555646864 In sequential one-test RED/GREEN cycles, add the service-priority `RelayPreflightInterlock`, wire every VPN/proxy start path to cancel and await an admitted preflight before protect/runtime setup, and prove both race orderings leave normal service startup unchanged #feature !high @item:RLY-1786618247484998 @blocked_by:RLY-1786618555627276
- [x] SVC-1786618555663036 In sequential one-test RED/GREEN cycles, add transient record resolution plus `ImportedRelayProfilePreflight`: ephemeral loopback SOCKS, exactly one TCP-only probe, typed secret-free outcomes, a 12-second total deadline, and bounded non-cancellable stop/cancel/join cleanup on success, failure, timeout, and caller cancellation #feature !high @item:RLY-1786618247484998 @blocked_by:SVC-1786618555646864
- [x] UIX-1786618555680732 In sequential one-test RED/GREEN cycles, integrate typed preflight state into `ProfileImportConfirmViewModel`, keep Add and Check mutually exclusive while work runs, clear stale results on a new profile, preserve advisory Add/import semantics, and assert no repository, activation, navigation, or secret-bearing state mutation occurs during a check #feature !high @item:RLY-1786618247484998 @blocked_by:SVC-1786618555663036
- [ ] UIX-1786618555696038 In sequential one-test RED/GREEN cycles, render the full-width secondary `Check profile` action and neutral result state, add complete en/ru/es/de/fr/fa/ar/zh-CN/hi resources and accessibility assertions, review or explicitly authorize the narrow profile-import screenshot delta, then run targeted suites, locale lint, `staticAnalysis`, architecture/task/OpenSpec gates, rebase validation, and physical Pixel 7 success/failure cleanup proof #feature !high @item:RLY-1786618247484998 @blocked_by:UIX-1786618555680732

## Verification

- `./gradlew :app:testGithubFullDebugUnitTest --tests 'com.poyka.ripdpi.proxyimport.*' --tests 'com.poyka.ripdpi.ui.screens.proxyimport.*' -Pripdpi.skipNativeBuild=true -x :app:verifyEmbeddedRelayBundle -x :core:engine:buildRustNativeLibs -x :core:engine:buildRustCloudflareOrigin -x :core:engine:buildRustNaiveProxy -x :core:engine:buildRustRootHelper --no-parallel --no-configuration-cache --no-daemon --console=plain`
- `./gradlew :core:service:testDebugUnitTest --tests 'com.poyka.ripdpi.services.ImportedRelayProfilePreflightTest' --tests 'com.poyka.ripdpi.services.RelayPreflightInterlockTest' -Pripdpi.skipNativeBuild=true -x :core:engine:buildRustNativeLibs -x :core:engine:buildRustCloudflareOrigin -x :core:engine:buildRustNaiveProxy -x :core:engine:buildRustRootHelper --no-parallel --no-configuration-cache --no-daemon --console=plain`
- Narrow `ProfileImportConfirmScreenshotTest` verify task; record only the affected profile-import fixture after explicit family authorization, then inspect its expected/actual/diff artifacts.
- `./gradlew :app:lintGithubFullDebug :core:service:lintDebug`
- `./gradlew staticAnalysis`
- `python3 scripts/ci/check_architecture_health.py`
- `./taskctl openspec cli validate add-safe-imported-profile-preflight --strict --json`
- `./taskctl generate-board && ./taskctl validate`
- After `git fetch origin && git rebase origin/main`, rerun all combined-tree local gates before fast-forwarding `main`.
- On a connected Pixel 7 and an owner-controlled supported relay: observe one successful target reachability result and one controlled failure, verify no retry/UDP probe, confirm the VPN/proxy service remains halted during the check, compare durable configuration before/after, and confirm no temporary listener, native handle, or coroutine remains.
- Hosted CI and physical-device evidence are reported independently; local green does not imply either.
