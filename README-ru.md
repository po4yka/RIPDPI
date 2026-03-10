# RIPDPI for Android

[English](README.md) | **Русский**

Приложение для Android, которое запускает локальный VPN-сервис для обхода DPI (Deep Packet Inspection) и цензуры.

Локально запускает SOCKS5-прокси на основе [ByeDPI](https://github.com/hufrea/byedpi) и перенаправляет весь трафик через него.

## Настройки

Для обхода некоторых блокировок может потребоваться изменить настройки. Подробнее в [документации ByeDPI](https://github.com/hufrea/byedpi/blob/v0.13/README.md).

## FAQ

**Приложение требует root?** Нет.

**Это VPN?** Нет. Приложение использует VPN-режим Android для локального перенаправления трафика. Оно не шифрует трафик и не скрывает ваш IP-адрес.

**Как использовать вместе с AdGuard?**
1. Запустите RIPDPI в режиме прокси.
2. Добавьте RIPDPI в исключения AdGuard.
3. В настройках AdGuard укажите прокси: SOCKS5, хост `127.0.0.1`, порт `1080`.

## Сборка

Требования: JDK 17+, Android SDK, Android NDK, Rust toolchain

```bash
git clone
./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/`

## CI/CD

Проект использует GitHub Actions для непрерывной интеграции и автоматизации релизов.

**CI** (`.github/workflows/ci.yml`) запускается при каждом push и PR в `main`:
- Сборка debug APK
- Запуск unit-тестов

**Release** (`.github/workflows/release.yml`) запускается при push тегов `v*` или вручную:
- Сборка подписанного release APK
- Создание GitHub Release с прикрепленным APK

### Необходимые GitHub Secrets

Для подписанных релизных сборок настройте секреты репозитория:

| Secret | Описание |
|--------|----------|
| `KEYSTORE_BASE64` | Keystore в Base64 (`base64 -i release.keystore`) |
| `KEYSTORE_PASSWORD` | Пароль keystore |
| `KEY_ALIAS` | Алиас ключа подписи |
| `KEY_PASSWORD` | Пароль ключа подписи |

## Native-модули

- Встроенный Rust-модуль на основе [ByeDPI](https://github.com/hufrea/byedpi)
- Встроенный Rust-модуль на основе [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)

Подробности об интеграции native-библиотек и используемых методах: [docs/native/README.md](docs/native/README.md)
