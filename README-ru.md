# RIPDPI for Android

[English](README.md) | **Русский**

Приложение для Android для обхода DPI (Deep Packet Inspection) и цензуры с:

- local proxy mode
- local VPN redirection mode
- шифрованным DNS в VPN-режиме через DoH/DoT/DNSCrypt
- встроенной диагностикой и пассивной telemetry
- in-repository Rust native modules

RIPDPI локально запускает SOCKS5-прокси на основе [ByeDPI](https://github.com/hufrea/byedpi). В VPN mode Android-трафик перенаправляется в этот локальный прокси через локальный TUN-to-SOCKS bridge на основе [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel).

## Диагностика

В RIPDPI есть встроенный экран диагностики для активных DPI-проверок и пассивного runtime-мониторинга.

Реализованные механизмы диагностики:

- Ручные сканы в режимах `RAW_PATH` и `IN_PATH`
- Проверка целостности DNS через UDP DNS и шифрованные резолверы (DoH/DoT/DNSCrypt)
- Проверка доступности доменов с классификацией TLS и HTTP
- Детект блокировки на пороге 16-20 КБ через fat-header requests
- Поиск обхода через whitelist SNI для заблокированных TLS-path
- Рекомендации по резолверу с временным session override и сохранением в настройки DNS
- Пассивная native-телеметрия во время работы proxy или VPN service
- Экспорт bundle с `summary.txt`, `report.json`, `telemetry.csv` и `manifest.json`

Что приложение сохраняет:

- Android network snapshot: transport, capabilities, DNS, MTU, локальные адреса, public IP/ASN, captive portal, validation state
- Native-телеметрию proxy runtime: lifecycle listener-а, принятых клиентов, выбор и переключение route, native-ошибки
- Native-телеметрию tunnel runtime: lifecycle туннеля, счётчики пакетов и байтов, resolver id/protocol/endpoint, DNS latency и failure counters, fallback reason, network handover class

Что приложение не сохраняет:

- Полные packet capture
- Payload пользовательского трафика
- TLS secrets

## Настройки

Для обхода некоторых блокировок может потребоваться изменить настройки. Подробнее в [документации ByeDPI](https://github.com/hufrea/byedpi/blob/v0.13/README.md).

## FAQ

**Приложение требует root?** Нет.

**Это VPN?** Нет. Приложение использует VPN-режим Android для локального перенаправления трафика. Оно не шифрует обычный пользовательский трафик и не скрывает ваш IP-адрес. При включенном encrypted DNS шифруются только DNS-запросы через DoH/DoT/DNSCrypt.

**Как использовать вместе с AdGuard?**

1. Запустите RIPDPI в proxy mode.
2. Добавьте RIPDPI в исключения AdGuard.
3. В настройках AdGuard укажите proxy: SOCKS5, хост `127.0.0.1`, порт `1080`.

## Документация

- [Native integration и использование модулей](docs/native/README.md)
- [Тесты, E2E, golden contracts и soak coverage](docs/testing.md)
- [Анализ внешних проектов и идеи для развития](docs/external-projects-analysis.md)

## Сборка

Требования:

- JDK 17
- Android SDK
- Android NDK `29.0.14206865`
- Rust toolchain `1.94.0`
- Android Rust targets для нужных ABI

Базовая локальная сборка:

```bash
git clone https://github.com/po4yka/RIPDPI.git
cd RIPDPI
./gradlew assembleDebug
```

Быстрая локальная native-сборка только для одного ABI:

```bash
./gradlew assembleDebug -Pripdpi.localNativeAbis=arm64-v8a
```

APK:

- debug: `app/build/outputs/apk/debug/`
- release: `app/build/outputs/apk/release/`

## Тестирование

В проекте есть многоуровневое покрытие для Kotlin, Rust, JNI, services, diagnostics, local-network E2E, Linux TUN E2E, golden contracts и native soak-запусков.

Основные команды:

```bash
./gradlew testDebugUnitTest
bash scripts/ci/run-rust-native-checks.sh
bash scripts/ci/run-rust-network-e2e.sh
```

Подробности и точечные команды: [docs/testing.md](docs/testing.md)

## CI/CD

Проект использует GitHub Actions для непрерывной интеграции и автоматизации релизов.

**CI для push / PR** (`.github/workflows/ci.yml`) сейчас запускает:

- `build`: сборку debug APK, ELF verification, native size verification, JVM unit tests
- `static-analysis`: Rust formatting/clippy/tests, cargo-deny, Android static analysis
- `rust-network-e2e`: repo-owned local-network proxy E2E и focused vendored parity smoke
- `android-network-e2e`: emulator-based instrumentation E2E поверх local fixture stack

**Nightly / manual CI** дополнительно запускает:

- `rust-native-soak`: host-side native soak для proxy и diagnostics runtime
- `linux-tun-e2e`: privileged Linux TUN E2E и TUN soak coverage

Workflow может сохранять golden diffs, Android reports, fixture logs и soak metrics.

**Release** (`.github/workflows/release.yml`) запускается при push тегов `v*` или вручную:

- Сборка подписанного release APK
- Создание GitHub Release с прикреплённым APK

### Необходимые GitHub Secrets

Для подписанных релизных сборок настройте секреты репозитория:

| Secret | Описание |
|--------|----------|
| `KEYSTORE_BASE64` | Keystore в Base64 (`base64 -i release.keystore`) |
| `KEYSTORE_PASSWORD` | Пароль keystore |
| `KEY_ALIAS` | Алиас ключа подписи |
| `KEY_PASSWORD` | Пароль ключа подписи |

## Native-модули

- `native/rust/crates/ripdpi-android`: JNI bridge прокси и поверхность proxy runtime telemetry
- `native/rust/crates/hs5t-android`: JNI bridge TUN-to-SOCKS и поверхность tunnel telemetry
- `native/rust/crates/ripdpi-monitor`: активные diagnostics scans и passive diagnostics events
- `native/rust/crates/ripdpi-dns-resolver`: общий encrypted DNS resolver для диагностики и VPN mode
- `native/rust/crates/ripdpi-runtime`: общий proxy runtime layer, используемый `libripdpi.so`
- `native/rust/crates/android-support`: Android logging и JNI support helpers

Подробности об интеграции native-библиотек и используемых методах: [docs/native/README.md](docs/native/README.md)
