# Licensing

Copyright (c) 2026 Nikita Pochaev.

RIPDPI is dual-licensed. You can use it under one of these two licenses:

1. The [GNU Affero General Public License, version 3 only](LICENSE) (`AGPL-3.0-only`).
2. A separate commercial license from the copyright holder.

## AGPL-3.0

The AGPL-3.0 applies to everyone by default. In short, it requires that:

- You keep the copyright notices and the license text, and you name the original author.
- When you distribute the app or a modified version, you give the recipients the complete corresponding source code under the AGPL-3.0.
- When users interact with a modified version over a network, you offer them its source code under the AGPL-3.0.
- You do not add further restrictions on the recipients.

The [LICENSE](LICENSE) file is the binding text. This summary does not replace it.

## Commercial license

Get a commercial license if you want to use RIPDPI, or a modified version of it, in a product or service and you cannot or do not want to meet the AGPL-3.0 terms, for example to keep your changes closed or to ship RIPDPI code inside proprietary software.

To ask for a commercial license, contact the copyright holder through [the GitHub profile](https://github.com/po4yka) and describe the intended use.

## Earlier releases

Commits before the license change were released under the BSD 3-Clause License. Those earlier copies stay available under their original terms. All later changes are under the terms in this file.

## Third-party components

Bundled third-party code keeps its own license. Its notices stay in the files or directories that contain it, for example `native/rust/vendor/`, `native/rust/crates/ripdpi-socks5-core/LICENSE`, and `core/engine/src/main/assets/lua/LICENSE.zapret2.txt`.

Parts of the native desync engine are derived from [byedpi](https://github.com/hufrea/byedpi), Copyright (c) 2024 hufrea, under the MIT License. This includes the command-line option set and constants in `native/rust/crates/ripdpi-config` and the default fake TLS ClientHello in `native/rust/crates/ripdpi-packets`. The license text is in [LICENSES/byedpi-MIT.txt](LICENSES/byedpi-MIT.txt).

The Snell client in `native/rust/crates/ripdpi-snell` is a port of [sagernet/sing-snell](https://github.com/SagerNet/sing-snell), Copyright (C) 2022 by nekohasekai, under the GNU General Public License, version 3 or later (`GPL-3.0-or-later`). GPLv3 section 13 permits its combination with the AGPL-3.0 code. That crate is excluded from the commercial license: a build under the commercial license must remove it together with the `snell` relay kind. The license text and notice are in [LICENSES/sing-snell-GPL-3.0-or-later.txt](LICENSES/sing-snell-GPL-3.0-or-later.txt).

## Contributions

To keep dual licensing possible, every contribution requires agreement to the [Contributor License Agreement](CLA.md).
