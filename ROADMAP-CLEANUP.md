# RIPDPI Cleanup Roadmap

Дорожная карта чистки и завершения недореализованных фич, выявленных аудитом 2026-04-27. Работы сгруппированы в 5 фаз по риску, зависимостям и ценности. В каждой фазе — задачи в формате `ID | путь | критерий готовности`.

Принципы:
- **Не расширять detekt/lint baselines** — новые правки не должны добавлять подавлений.
- **Не ломать non-rooted baseline** — root-only фичи остаются opt-in.
- **Каждая задача = отдельный PR** с прогоном `cargo nextest` (Rust) и `gradle :app:test :app:lint` (Kotlin) до коммита.
- **Для каждой задачи фиксировать success criteria** (что именно проверяет, что готово).

---

## Phase 1 — Quick Wins (orphans + missing UI)

**Цель:** удалить мёртвый код, выставить наружу скрытые настройки, поправить очевидные UX-провалы. Низкий риск, отдельные диффы 50–200 строк.

### 1.1 Удаление/перевод test-support крейтов в отдельный workspace

| ID | Путь | Done when |
|---|---|---|
| P1.1.1 | `native/rust/Cargo.toml` (members) | `golden-test-support`, `local-network-fixture`, `native-soak-support` вынесены в `native/rust/test-support/Cargo.toml` или помечены `publish = false` + явный `[workspace.metadata]` тег `test-only`. APK-target не меняется. |
| P1.1.2 | `native/rust/crates/ripdpi-bench/Cargo.toml` | Помечен `publish = false`, `[lib]` либо вынесен. CI продолжает запускать криterion бенчи. |
| P1.1.3 | `native/rust/crates/ripdpi-cli/Cargo.toml` | Документировано в README крейта, что бинарь только для локальной отладки и не поставляется в APK. |

### 1.2 Удаление мёртвых вариантов API

| ID | Путь | Done when |
|---|---|---|
| P1.2.1 | `native/rust/crates/ripdpi-runtime/src/platform/capabilities.rs:56` | `CapabilityUnavailable::NotImplemented` — либо удалён (если не используется), либо подключён в как минимум один реальный call-site (`platform/linux/*` где capability в работе). Решение задокументировать в коммит-сообщении. |

### 1.3 Выставление наружу или удаление `community_api_url`

| ID | Путь | Done when |
|---|---|---|
| P1.3.1 | `core/data/model/src/main/proto/app_settings.proto:323` + `app/.../SettingsUiStateFactory.kt` + `SettingsCustomizationActions` | Принять решение: (a) добавить UI-поле с валидацией URL и ссылкой "Reset to default" в Privacy/Detection секции, либо (b) удалить proto-поле и захардкодить `DEFAULT_STATS_URL`. Если (a) — добавить тест UI-state-factory. |

### 1.4 UX-улучшения DetectionCheckScreen

| ID | Путь | Done when |
|---|---|---|
| P1.4.1 | `app/.../DetectionCheckScreen.kt:378-379` | `CommunityStatsCard` показывает loading-индикатор пока `communityStats == null`, и явное error-state если запрос провалился. UI-тест на оба состояния. |
| P1.4.2 | `app/.../SettingsPreferencesScreen.kt:264-270` | `fullTunnelMode` toggle сопровождается helper-text объясняющим, что split-tunnel опции отключаются. Локализовано (en/ru). |
| P1.4.3 | `app/.../SettingsUiStateFactory.kt:531` | Когда `seqovlSupported == false`, в SeqOverlap-секции показывается причина (через `CapabilityUnavailable` reason). |

### 1.5 Browser route в навигации

| ID | Путь | Done when |
|---|---|---|
| P1.5.1 | `app/.../OwnedStackBrowserScreen.kt` + nav | Решение зафиксировано: либо browser добавлен в bottom nav (с feature flag), либо документировано в README что это remediation-only entry point. |

**Phase 1 verification:** `gradle :app:detekt :app:lint :app:test`, `cargo nextest run --workspace`. Diff per task ≤ 200 строк.

---

## Phase 2 — Correctness (concurrency, cancellation, logging)

**Цель:** закрыть найденные аудитом дефекты, связанные с памятью, отменой корутин и privacy. Средний риск (трогает hot path), требует тщательного тестирования.

### 2.1 Atomic ordering и ownership Rust

| ID | Путь | Done when |
|---|---|---|
| P2.1.1 | `native/rust/crates/ripdpi-native-protect/src/lib.rs:53` | `last_fd: AtomicI32` использует `Ordering::Release` для store и `Ordering::Acquire` для load. Добавить SAFETY-комментарий с обоснованием. |
| P2.1.2 | `native/rust/crates/ripdpi-tunnel-core/src/io_loop/udp_assoc.rs:21,52,58,62` | `Arc<Mutex<StdInstant>>` заменён на `AtomicU64` (миллисекунды от epoch или monotonic clock). Бенч `ripdpi-bench` показывает отсутствие регрессии. |
| P2.1.3 | `ripdpi-tunnel-core/src/io_loop.rs:109`, `tunnel_api.rs:62`, `ripdpi-io-uring/src/ring.rs:31-38` | `RawFd` заменён на `OwnedFd` где ownership подразумевается. Где fd передаётся через JNI с явным контрактом — оставлен `RawFd` с комментарием о ownership boundary. |

### 2.2 Coroutine cancellation hygiene

| ID | Путь | Done when |
|---|---|---|
| P2.2.1 | `core/detection/.../CommunityComparisonClient.kt:40` | `catch (e: Exception)` заменён на специфичные исключения, либо добавлен `if (e is CancellationException) throw e`. Тест проверяет что cancel не глотается. |
| P2.2.2 | `core/detection/.../GeoIpChecker.kt:37` | То же. |
| P2.2.3 | `core/detection/.../IndirectSignsChecker.kt:178/249/304/414/494/582` | 6 catch-блоков обновлены. Один общий тест на cancellation propagation для всего checker'а. |
| P2.2.4 | `core/service/.../Tun2SocksTunnel.kt:115` | То же. |
| P2.2.5 | `app/.../OnboardingConnectionTestRunner.kt:48` | То же. |

### 2.3 Privacy / logging hygiene

| ID | Путь | Done when |
|---|---|---|
| P2.3.1 | `core/service/.../RipDpiVpnService.kt:241` | `Logger.v { "DNS: $dns" }` либо удалён, либо ограничен debug-builds через `Timber` release-tree. Lint-правило (если возможно) добавлено в detekt. |
| P2.3.2 | `quality/detekt-rules/` | Добавить custom detekt-правило `NoResolverIpInLogs` запрещающее логирование переменных с именами `dns`, `resolver`, `upstreamIp`. |

### 2.4 Foreground service lifecycle

| ID | Путь | Done when |
|---|---|---|
| P2.4.1 | `core/service/.../ServiceManager.kt:66,75,92,101` | Каждый `startForegroundService` обёрнут в try/catch с обработкой `ForegroundServiceStartNotAllowedException` (API 31+). Fallback-стратегия: показать notification-only mode. |
| P2.4.2 | `core/detection/.../DetectionCheckScheduler.kt:128-135` | `NotificationChannel` создаётся при инициализации сервиса (не в `postNotification()`). Добавить `@Before` в инструментальном тесте. |

### 2.5 Hilt и context safety

| ID | Путь | Done when |
|---|---|---|
| P2.5.1 | `core/detection/.../DetectionHistoryStore.kt` | Конструктор принимает `@ApplicationContext Context` через Hilt-инъекцию (или явный фабричный метод требующий `Application`). |

**Phase 2 verification:** прогон Miri (где применимо), `cargo nextest`, инструментальные тесты на API 31+/34, ручной тест cancellation в Detection screen.

---

## Phase 3 — Architectural Decisions

**Цель:** принять решения по парности фич между Kotlin и Rust, и по orphan-функциональности. Высокий риск, требует архитектурного обсуждения.

### 3.1 Cloudflare publish_local parity

| ID | Путь | Done when |
|---|---|---|
| P3.1.1 | `native/rust/crates/ripdpi-relay-core/src/lib.rs` | Либо в `relay_runtime_*` функциях добавлен match/dispatch по `cloudflare_tunnel_mode` со специфичной логикой для `publish_local`, либо `cloudflare_publish_local_origin_url` и `cloudflare_credentials_ref` помечены deprecated и удалены из `ResolvedRelayRuntimeConfig`. ADR в `docs/architecture/`. |
| P3.1.2 | `core/service/.../CloudflarePublishRuntime.kt` | Если 3.1.1 решено в пользу удаления — удалить Kotlin-runtime; если в пользу поддержки — добавить интеграционный тест. |

### 3.2 Tier-3 desync (SynHide / IcmpWrappedUdp)

| ID | Путь | Done when |
|---|---|---|
| P3.2.1 | `native/rust/crates/ripdpi-runtime/src/platform/experimental_tier3.rs` + `linux/experimental_tier3.rs` | Решение: (a) подключить через новый `DesyncMode::SynHide` / `DesyncMode::IcmpWrappedUdp` + UI-поля + packet-smoke тесты, либо (b) удалить как mvp-эксперимент. |
| P3.2.2 | `core/data/model/src/main/proto/app_settings.proto` | Если (a) — расширить `DesyncMode` enum + миграция; если (b) — никаких изменений. |

### 3.3 RelaySession::open_datagram

| ID | Путь | Done when |
|---|---|---|
| P3.3.1 | `native/rust/crates/ripdpi-relay-mux/src/lib.rs:68` | Либо UDP-путь в `ripdpi-relay-core` переведён на `RelaySession::open_datagram` (унификация), либо метод удалён из трейта (TCP-only contract). Решение в ADR. |

### 3.4 Finalmask UI exposure

| ID | Путь | Done when |
|---|---|---|
| P3.4.1 | `app/.../RelayFields.kt` + `ModeEditorScreen.kt` | Либо добавлены явные поля `sudoku_seed`, `rand_range`, `fragment_packets/min/max` в Mode Editor (с валидацией), либо документировано что они доступны только через `chain_dsl` advanced text. |

### 3.5 TLS profiles parity

| ID | Путь | Done when |
|---|---|---|
| P3.5.1 | `native/rust/crates/ripdpi-tls-profiles/src/edge.rs`, `safari.rs` | Либо профили дополнены до уровня chrome/firefox (extensions, GREASE, ALPN порядок), либо удалены из публичного API с явным fallback на chrome. Golden-фикстуры обновлены. |

**Phase 3 verification:** ADR создан и принят, golden contract tests проходят, packet-smoke сценарии добавлены/обновлены.

---

## Phase 4 — Feature Completion

**Цель:** закрыть оставшиеся "PARTIAL" статусы из основного ROADMAP. Долгий горизонт, каждая задача — отдельный эпик.

### 4.1 Strategy Evolver host-pack wiring (ROADMAP:193)

| ID | Путь | Done when |
|---|---|---|
| P4.1.1 | host-pack schema | Schema bump: 4 поля (`experiment_ttl_ms`, `decay_half_life_ms`, `cooldown_after_failures`, `cooldown_ms`) добавлены с дефолтами. Contract-фикстуры регенерированы. |
| P4.1.2 | `core/data/model/src/main/proto/app_settings.proto` | Соответствующие proto-поля добавлены. |
| P4.1.3 | `app/.../AdvancedSettingsHandlerRegistry.kt` + `StrategyPackSection.kt` | Карточка "Strategy Evolver Tuning" с 4 числовыми полями, диапазонами, описаниями. |
| P4.1.4 | ingestion plumbing | Host-pack -> RuntimeAdaptiveSettings -> StrategyEvolver flow покрыт интеграционным тестом. |

### 4.2 DNS enforcement (ROADMAP:116)

| ID | Путь | Done when |
|---|---|---|
| P4.2.1 | DNS cache layer | Per-app-family invalidation на смену версии пакета. Тест на `PackageManager.GET_PACKAGE_VERSION` change. |
| P4.2.2 | DNS resolver selection | Dedicated `(host, NetProfile)` fastest-resolver cache. Бенч показывает <X% latency improvement. |

### 4.3 Direct-Mode Diagnostic Orchestrator (ROADMAP:138)

| ID | Путь | Done when |
|---|---|---|
| P4.3.1 | `runtime/direct_path_learning.rs` | Explicit ranked-arm dispatcher реализован. |
| P4.3.2 | runtime | Per-class attempt-budget enforcement с метриками. |
| P4.3.3 | tests | Детерминированное интеграционное покрытие full class-to-arm execution ladder. |
| P4.3.4 | `ripdpi-config` | Config relay preset suggestions используют тот же transport-remediation selector. |

### 4.4 Offline Learner improvements (ROADMAP:164)

| ID | Путь | Done when |
|---|---|---|
| P4.4.1 | `ripdpi-runtime/src/strategy_evolver.rs` | Bayesian arm scoring (Thompson sampling или beta-bernoulli) альтернативой UCB1, выбираемой через config. |
| P4.4.2 | strategy_evolver | Rarity/retry penalties в scoring formula. |
| P4.4.3 | strategy_evolver | Attempt-budget enforcement. |
| P4.4.4 | shared-priors | Upload constraints (max payload size, rate limit) для shared priors. |
| P4.4.5 | calibration | Emulator/sim-to-field calibration beyond field-derived archive mining. |

### 4.5 Stage timing instrumentation

| ID | Путь | Done when |
|---|---|---|
| P4.5.1 | diagnostics pipeline | Per-stage timestamps (`dpi_strategy`, `connectivity`, `automatic_audit`, `dns_baseline`) инструментированы через единый `StageTimer`. |
| P4.5.2 | `app/.../DefaultDeveloperAnalyticsSource.kt:433` | Placeholder removed, реальные тайминги в developer analytics экспорт. |

**Phase 4 verification:** обновить статус каждого эпика в основном `ROADMAP.md` с PARTIAL → COMPLETE по мере закрытия.

---

## Phase 5 — Strategic / Performance

**Цель:** перформанс-оптимизации и устойчивость к долгосрочным изменениям окружения. Низкая срочность, высокая ценность.

### 5.1 ECH config rotation

| ID | Путь | Done when |
|---|---|---|
| P5.1.1 | `native/rust/crates/ripdpi-monitor/src/cdn_ech.rs:105` | Periodic refresh механизм (по аналогии с `VpnAppCatalogUpdater`) обновляет ECH-конфиг с TTL 24h. Source — публичный DoH `_ech-config.cloudflare-dns.com` или эквивалент. Fallback на bundled config при недоступности. |
| P5.1.2 | tests | Unit + integration тест на устаревший конфиг и rollover. |

### 5.2 io_uring оптимизации

| ID | Путь | Done when |
|---|---|---|
| P5.2.1 | `ripdpi-runtime/src/runtime/relay/stream_copy_uring.rs:170` | Condvar/eventfd для пробуждения downstream треда вместо busy-wait. Бенч на idle connections показывает CPU drop. |
| P5.2.2 | `ripdpi-io-uring/src/tun.rs:87` | Registered-buffer вариант для tx_queue. Бенч на throughput сравнивает с baseline. |

### 5.3 CommunityComparisonStore policy

| ID | Путь | Done when |
|---|---|---|
| P5.3.1 | `core/detection/.../CommunityComparisonStore.kt` | TTL для кеша (например 24h), кнопка "Clear community cache" в Privacy settings. |

### 5.4 SharingStarted.Eagerly review

| ID | Путь | Done when |
|---|---|---|
| P5.4.1 | `app/.../MainViewModel.kt:297,368`, `core/service/.../ActiveConnectionPolicyStore.kt:86` (+ оставшиеся 2) | Каждый случай либо переведён на `WhileSubscribed(5_000)`, либо снабжён комментарием объясняющим необходимость Eager (например, прогрев native-binding на старте). |

### 5.5 Apps Script relay UI

| ID | Путь | Done when |
|---|---|---|
| P5.5.1 | `core/service/.../GoogleAppsScriptRelayRuntime.kt` + `app/.../RelayFields.kt` | Решение: добавить Apps Script preset с явным URL/auth полями, либо deprecate runtime если фича не востребована. |

**Phase 5 verification:** перформанс-бенчи (criterion + macrobenchmark), проверка APK size baseline (`native-bloat`).

---

## Зависимости и порядок

```
Phase 1 ──► Phase 2 ──► Phase 4
   │           │            ▲
   ▼           ▼            │
Phase 3 ──────────────► Phase 5
```

- **Phase 1 не блокирует ничего** — можно начинать сразу, параллельно несколькими PR.
- **Phase 2 предшествует Phase 4**: cancellation hygiene нужна до того, как добавлять новые async paths в Direct-Mode dispatcher.
- **Phase 3 (3.1, 3.2) предшествует Phase 5**: решение по Cloudflare/Tier-3/Finalmask формирует поверхность для перформанс-работы.
- **Phase 4 и Phase 5 независимы** друг от друга после Phase 2/3.

## Метрики готовности фазы

| Phase | Definition of Done |
|---|---|
| 1 | Все `community_api_url` упоминания закрыты, test-support split landed, 0 orphan-вариантов в публичных enum-ах. |
| 2 | rust-api-auditor аудит проходит без HIGH-ов, kotlin-design-auditor — 0 swallowed `CancellationException`, detekt rule `NoResolverIpInLogs` activated. |
| 3 | 4 ADR опубликованы, all PARTIAL-парности либо завершены, либо явно deprecated. |
| 4 | 4 ROADMAP slices с PARTIAL → COMPLETE, stage timings реальные. |
| 5 | ECH rotation активен, io_uring CPU/throughput регрессий нет, нет orphan UI/runtime в production. |

## Tracking

- Каждый ID = одна issue/PR.
- Чек-листы фаз вести в `docs/cleanup-progress.md` (создать при старте Phase 1).
- При закрытии фазы — апдейт основного `ROADMAP.md` с пометкой "Cleanup Phase N: COMPLETE".
