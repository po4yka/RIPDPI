# CLAUDE.md -- RIPDPI

See AGENTS.md for full project context.

## Rules

- **Never extend baselines** (detekt baselines, LoC baselines, lint baselines) to suppress new violations. Always fix the underlying issue -- refactor long files, resolve detekt findings, etc. Baselines exist only for legacy debt; new code must not add to them.
- **Non-rooted Android baseline** -- the app must fully function on non-rooted Android devices. Root-only features (ripdpi-root-helper, FakeRst, MultiDisorder, IpFrag2) are opt-in behind the `root_mode_enabled` setting and must degrade gracefully when root is unavailable.
- **No backend server** -- the app has no backend and will not have one. All features must work fully offline and locally. Do not design features that require a server, API endpoint, or remote service operated by the project. External data (host packs, community stats) must use static files hosted on GitHub or bundled in assets. User data never leaves the device unless the user explicitly exports/shares it.
- **Goal-driven execution** -- before implementing, convert the task into concrete, verifiable success criteria. State what "done" looks like (test passes, metric improves, UI renders correctly) and verify each criterion before reporting completion. When criteria are ambiguous, ask for clarification rather than guessing.

## Coding Discipline (adapted from Karpathy guidelines)

Behavioural guardrails to reduce common LLM coding mistakes. Bias toward caution over speed; for trivial edits, use judgement. Source: https://github.com/forrestchang/andrej-karpathy-skills (MIT).

### 1. Think Before Coding

Don't assume. Don't hide confusion. Surface tradeoffs.

- State assumptions explicitly; if uncertain, ask before writing code.
- When multiple interpretations exist (e.g. "add a probe" could mean DNS, TLS, or QUIC), present options rather than picking silently.
- If a simpler approach exists (e.g. extend an existing probe instead of adding a new crate), say so and push back when warranted.
- If something is unclear -- an ambiguous DesyncMode, an undocumented JNI contract, a missing schema migration -- stop, name the confusion, ask.

### 2. Simplicity First

Minimum code that solves the problem. Nothing speculative.

- No features beyond what was asked.
- No abstractions for single-use code (no traits with one impl, no sealed class hierarchies for one variant).
- No "flexibility" or "configurability" that was not requested.
- No error handling for impossible scenarios -- trust internal invariants; validate at boundaries (JNI, user input, external DNS/DoH responses).
- If 200 lines could be 50, rewrite it. Senior-engineer sniff test: would a staff engineer call this overcomplicated?

### 3. Surgical Changes

Touch only what you must. Clean up only your own mess.

- Don't "improve" adjacent code, comments, or formatting while fixing a bug.
- Don't refactor untouched modules; match existing Kotlin/Rust style even if you'd do it differently.
- If you notice unrelated dead code or a suspect pattern, mention it -- don't delete it unsolicited.
- Remove imports/variables/functions that *your* changes orphaned; do not prune pre-existing dead code unless asked.
- Every changed line should trace directly to the user's request. Unrelated detekt/clippy fixes belong in their own commit.

### 4. Goal-Driven Execution

Reinforces the rule above. Transform tasks into verifiable loops:

- "Add validation" -> write tests for invalid inputs, then make them pass.
- "Fix the bug" -> write a failing test (or reproduce via packet-smoke / cargo-nextest), then make it pass.
- "Refactor X" -> ensure tests pass before and after; diff behaviour against main when relevant.

For multi-step tasks, state a brief plan with per-step verification (test name, metric, or command) before implementing. Strong success criteria let you iterate independently; weak ones ("make it work") force clarification mid-flight.

**These guidelines are working if:** diffs contain no drive-by changes, rewrites due to overcomplication drop, and clarifying questions arrive before implementation rather than after mistakes.

## Kotlin Anti-Patterns

### Coroutines, state, Compose

- **Blocking coroutines on Main** -- never use `runBlocking` on the main thread.
- **GlobalScope usage** -- use structured concurrency with `viewModelScope`/`lifecycleScope`.
- **Collecting flows in `init`** -- use `repeatOnLifecycle` or `collectAsStateWithLifecycle`.
- **Mutable state exposure** -- expose `StateFlow`, not `MutableStateFlow`.
- **Not handling exceptions in flows** -- always use the `catch` operator.
- **`lateinit` for nullable** -- use `lazy` or nullable with `?`.
- **Hardcoded dispatchers** -- inject dispatchers for testability.
- **Not using sealed classes** -- prefer sealed for finite state sets.
- **Side effects in Composables** -- use `LaunchedEffect`/`SideEffect`.
- **Unstable Compose parameters** -- use stable/immutable types or `@Stable`.

### Memory & resource leaks

- No `Activity`/`Context` references in singletons or companion objects; use `@ApplicationContext` through Hilt.
- Always unregister `BroadcastReceiver`, `ContentObserver`, and lifecycle observers symmetrically with where they were registered.
- Close `Cursor`, `InputStream`, `ParcelFileDescriptor`, and other `Closeable` instances via `use {}`.
- Call `TypedArray.recycle()` after reading styled attributes.

### Coroutine cancellation correctness

- Never swallow `CancellationException`; rethrow it in any generic `catch (e: Throwable)`.
- Use `withContext(NonCancellable)` only for short cleanup inside `finally` blocks.
- Cleanup work that must survive cancellation (closing sockets, releasing VPN fds) belongs inside `NonCancellable` + `finally`, not outside it.
- See also: `kotlin-test-patterns`.

### Flow hot-path discipline

- `shareIn(SharingStarted.Eagerly)` leaks across config changes -- prefer `WhileSubscribed(5_000)`.
- Keep `stateIn`/`shareIn` inside `viewModelScope`; never pin them to a global or application-scoped job.
- `conflate()` drops items, `buffer()` preserves them -- pick deliberately based on whether missed emissions are acceptable.
- See also: `android-compose-patterns`, `compose-performance`.

### Foreground service discipline

- Call `startForeground()` within 5s of `onStartCommand`; missing this throws `ForegroundServiceDidNotStartInTimeException`.
- Create the notification channel before `startForeground`, not inside the foreground lifecycle.
- Handle `ForegroundServiceStartNotAllowedException` on API 31+ (apps in the background cannot start a foreground service without a qualifying reason).
- See also: `service-lifecycle`.

### Security & logging

- No secrets, tokens, resolver IPs, user-visible URLs, or tunnelled traffic in `Log.*`.
- No `Log.d`/`Log.v` in release builds; use `Timber` with a release `Tree` that strips or gates by severity.
- Avoid `WebView.setAllowFileAccess(true)` and `setAllowUniversalAccessFromFileURLs(true)`.
- Never persist to `MODE_WORLD_READABLE`/`MODE_WORLD_WRITEABLE` storage; app-private or EncryptedFile only.

### Serialization stability

- `@SerialName` values are a wire contract -- never rename them as part of a refactor.
- All cross-boundary `@Serializable` fields must have defaults, be nullable, or be `@Transient` with a default.
- Keep Kotlin/Rust wire structs field-order-aligned; mismatched ordering breaks golden contract tests.
- See also: `protobuf-schema-evolution`, `protobuf-datastore`.

Existing custom detekt rules live in `quality/detekt-rules/` (`InjectConstructorDefaultParameter`, `HiltViewModelApplicationContext`, `DisallowNewSuppression`); consult `detekt-custom-rules` skill before inventing new ones.
