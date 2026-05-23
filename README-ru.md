<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="120" alt="RIPDPI Logo"/>
</p>

<h1 align="center">RIPDPI</h1>
<p align="center"><b>Routing & Internet Performance Diagnostics Platform Interface</b></p>

<p align="center">
  <a href="https://github.com/po4yka/RIPDPI/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/po4yka/RIPDPI/ci.yml?style=flat-square&label=CI" alt="CI"/></a>
  <a href="https://github.com/po4yka/RIPDPI/releases/latest"><img src="https://img.shields.io/github/v/release/po4yka/RIPDPI?style=flat-square" alt="Release"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/po4yka/RIPDPI?style=flat-square" alt="License"/></a>
  &nbsp;
  <img src="https://img.shields.io/badge/Android-8.1%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.1+"/>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Rust-000000?style=flat-square&logo=rust&logoColor=white" alt="Rust"/>
</p>

<p align="center"><a href="README.md">English</a> | <b>Русский</b> | <a href="README-es.md">Español</a> | <a href="README-de.md">Deutsch</a> | <a href="README-fr.md">Français</a> | <a href="docs/fa/README.md">فارسی</a> | <a href="README-zh-CN.md">简体中文</a></p>

> [!WARNING]
> **Проект находится в активной фазе разработки.** Добавляются новые функции, часто проводятся крупные рефакторинги для повышения качества кодовой базы. В работе активно используются coding-агенты, поэтому в ветке `main` в настоящее время **возможны breaking changes, миграции схем и частично неработающая функциональность**. Если вы столкнулись с регрессией, пожалуйста, [создайте issue](https://github.com/po4yka/RIPDPI/issues) — ваша обратная связь помогает стабилизировать проект.

RIPDPI — это набор инструментов Android для диагностики и оптимизации сетевого пути. Он применяет настраиваемые packet-стратегии на устройстве, может подключаться к relay-серверам, которые вы контролируете, и выполняет диагностику для каждого соединения, чтобы определить, почему конкретная цель не работает или деградирует. Три возможности работают независимо или вместе.

## Три столпа

### Packet-стратегии на устройстве

Применяет настраиваемые преобразования на уровне пакетов на устройстве без маршрутизации трафика через relay-сервер. Root для основного пути не требуется.

Поддерживаемые техники: TCP-сегментация и disorder, инъекция fake-пакетов, OOB (urgent pointer), фрагментация TLS-записей, fake TLS first-flight, вариация QUIC handshake, нормализация DTLS-fingerprint, вариация UDP length-field, вставка IPv6 extension-headers, отправка raw-пакетов через Lua и адаптивные семантические маркеры, разрешающие позицию по live `TCP_INFO`. Strategy chains собираются из Rust-крейтов этого репозитория, без внешнего strategy-бинарника.

Когда relay не настроен, трафик выходит с устройства напрямую — мутации на устройстве являются единственным изменением пути.

### VPN-relay

Цепляет трафик локального прокси или VPN через зашифрованные relay-протоколы к серверу, который вы настраиваете:

- **VLESS Reality и xHTTP** — нативная реализация на Rust, без Go-runtime
- **WARP, Cloudflare Tunnel, MASQUE**
- **Hysteria2, TUIC v5, ShadowTLS v3, NaiveProxy**
- **AmneziaWG** — WireGuard с обфускацией handshake для сетей с высокой цензурой
- **WebTunnel, obfs4, Snowflake, путь через Google Apps Script**

И режим локального прокси, и режим перенаправления Android VPN работают с настроенным relay или без него.

### Диагностика

Сканирует каждую цель соединения независимо и выдаёт типизированный вердикт:

- `TRANSPARENT_WORKS` — прямой путь работает, вмешательство не требуется
- `OWNED_STACK_ONLY` — работает только через собственный TLS-стек приложения
- `NO_DIRECT_SOLUTION` — мутации на устройстве не могут восстановить эту цель; требуется relay
- `IP_BLOCK_SUSPECT` — обнаружена блокировка на уровне IP

Вердикты сохраняются по network fingerprint и автоматически воспроизводятся при повторном подключении к той же сети. Экран диагностики добавляет TCP- и QUIC-strategy probing по 24 TCP- и 6 QUIC-кандидатам, детектирование вмешательства DNS, рекомендации DoH/DoT/DNSCrypt/DoQ-резолверов и экспортируемые архивы диагностики.

## Зачем RIPDPI

Современные Android-сети регулярно применяют L7-fingerprinting (TLS JA3/JA4, QUIC), агрессивный QoS в сотовых и общедоступных Wi-Fi сетях, рассинхронизацию MTU и ECN и middlebox-induced TLS handshake aborts — из-за этого одни цели не работают, а другие в той же сети работают нормально. Один глобальный параметр не может покрыть все случаи.

Принцип проектирования RIPDPI: классифицировать каждую цель и каждую сеть отдельно, применять самое лёгкое работающее исправление и запоминать его.

1. **Ответ для каждой цели и каждой сети** — а не единая глобальная политика. Диагностика классифицирует каждый authority и сохраняет вердикт по хешу network fingerprint.
2. **Мутировать локальный путь, когда проблема в сети.** Семантические маркеры, adaptive split placement, fake-payload chains, OOB/disorder, randomized TLS records, вариация QUIC- и DTLS-fingerprint — собираются из in-repo Rust-крейтов.
3. **Откатываться на tunneled relay, когда прямой путь деградирован.** Native-Rust VLESS Reality/xHTTP, а также WARP, MASQUE, Hysteria2, TUIC v5, ShadowTLS v3, NaiveProxy, AmneziaWG и Cloudflare Tunnel обрабатывают цели, которые невозможно восстановить на устройстве.
4. **Честная отчётность.** Вердикты типизированы и отображаются; результаты failure classifier выводятся, а не подавляются; диагностические export bundles редактируют секреты.

## Скриншоты

<p align="center">
  <img src="docs/screenshots/01-hero.png" width="200" alt="RIPDPI home screen"/>
  &nbsp;
  <img src="docs/screenshots/02-no-root.png" width="200" alt="RIPDPI without root"/>
  &nbsp;
  <img src="docs/screenshots/03-privacy.png" width="200" alt="RIPDPI privacy screen"/>
  &nbsp;
  <img src="docs/screenshots/04-controls.png" width="200" alt="RIPDPI controls"/>
</p>
<p align="center">
  <img src="docs/screenshots/05-diagnostics.png" width="200" alt="RIPDPI diagnostics"/>
  &nbsp;
  <img src="docs/screenshots/06-more-features.png" width="200" alt="RIPDPI feature overview"/>
</p>

## Возможности

- **Режим прокси**: локальный SOCKS5-прокси на настроенном localhost-порте.
- **Режим VPN**: маршрутизирует трафик Android-устройства через локальный TUN-to-SOCKS bridge с использованием `VpnService`.
- **Импорт профилей**: сканирование и генерация QR-кода, а также импорт прокси-URI через буфер обмена и share-sheet (`vless://`, `hysteria2://`, `ss://`, `amneziawg://` и другие).
- **Подписки**: форматы подписок base64, Clash / Clash.Meta YAML, sing-box JSON и WireGuard-INI с фоновым автообновлением, обнаружением дублирующихся профилей, группами selector/urltest и доставкой через несколько зеркал.
- **Зашифрованный DNS**: поддержка DoH, DoT, DNSCrypt и DoQ-резолверов в путях, связанных с VPN.
- **Управление стратегиями**: семейства TCP split/disorder/fake, фрагментация TLS-записей и fake-профили, вариация QUIC- и DTLS-handshake, вариация UDP length-field, IPv6 extension headers, Lua `rawsend`, per-step activation filters, контроль IPv4 ID и OOB-инъекция.
- **Память политики по сети**: валидированные per-authority вердикты, индексированные по network fingerprint; автоматически воспроизводятся при переподключении.
- **Адаптивное probing**: автоматическое probing стратегий для впервые увиденных сетей; фоновая `quick_v1` перепроверка при network handover.
- **Перезапуск с учётом handover**: live-переоценка политики при переходах между Wi-Fi, сотовой связью и роумингом.
- **RIPDPI Browser**: собственный браузер приложения для HTTPS-целей, которые требуют собственный TLS-стек; общий путь `SecureHttpClient` для запросов, инициированных приложением.
- **Runtime-телеметрия и логи**: жизненный цикл прокси, route decisions, события DNS failover, прогресс диагностики и события native runtime — доступны как in-app history и support export.
- **Опциональный root-помощник**: на rooted-устройствах разблокирует операции с raw-сокетами (FakeRst, MultiDisorder, фрагментация IP, полный SeqOverlap, эмиссия raw IPv4/IPv6 пакетов) через привилегированный helper-процесс.

## Режимы выполнения

### Прокси

SOCKS5-прокси на настроенном localhost-порте. Для приложений, которые поддерживают конфигурацию прокси. Strategy-мутации и relay-chaining применяются ко всему трафику, входящему через прокси.

### VPN

Использует Android `VpnService` для перенаправления трафика устройства через локальный движок RIPDPI. Когда relay не настроен, режим VPN применяет мутации на устройстве без изменения egress IP. Когда relay настроен, трафик пересылается зашифрованным до настроенного endpoint.

## Приватность

RIPDPI записывает операционные метаданные для диагностики и устранения неполадок: снимки сети, статус резолвера, route decisions, результаты сканирования, состояние сервиса и события native runtime.

RIPDPI не записывает:
- Полные packet captures
- Полезную нагрузку трафика
- TLS-секреты

Приватность relay-трафика зависит от endpoint relay и профиля, который вы настраиваете.

## Сборка

Требования: JDK 17, Android SDK, Android NDK `29.0.14206865`, Rust toolchain `1.94.0`, Android Rust targets для нужных ABI.

```bash
git clone https://github.com/po4yka/RIPDPI.git
cd RIPDPI
./gradlew assembleDebug
```

Локальные сборки по умолчанию используют `arm64-v8a` (`ripdpi.localNativeAbisDefault`). Для эмулятора: `./gradlew assembleDebug -Pripdpi.localNativeAbis=x86_64`.

Вывод APK: `app/build/outputs/apk/debug/` и `app/build/outputs/apk/release/`.

## Тестирование

```bash
./gradlew testDebugUnitTest
bash scripts/ci/run-rust-native-checks.sh
bash scripts/ci/run-rust-network-e2e.sh
python3 -m unittest scripts.tests.test_offline_analytics_pipeline
```

Подробности: [docs/testing.md](docs/testing.md)

## Документация

- [Native-интеграция и модули](docs/native/README.md)
- [Packet strategy runtime](docs/packet-strategy-runtime.md)
- [Proxy engine и strategy surface](docs/native/proxy-engine.md)
- [TUN-to-SOCKS bridge](docs/native/tunnel.md)
- [Эксплуатация strategy-pack и TLS catalog](docs/strategy-pack-operations.md)
- [Примеры relay-профилей](docs/relay-profile-examples.md)
- [Архитектурные заметки](docs/architecture/README.md)
- [Roadmap](ROADMAP.md)
