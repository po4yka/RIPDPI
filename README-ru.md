# RIPDPI for Android

[English](README.md) | **Русский**

Приложение для Android, которое запускает локальный VPN-сервис для обхода DPI (Deep Packet Inspection) и цензуры.

Локально запускает SOCKS5-прокси [ByeDPI](https://github.com/hufrea/byedpi) и перенаправляет весь трафик через него.

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

Требования: JDK 8+, Android SDK, Android NDK, CMake 3.22.1+

```bash
git clone --recurse-submodules
./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/`

## Зависимости

- [ByeDPI](https://github.com/hufrea/byedpi)
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
