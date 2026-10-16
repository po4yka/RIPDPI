# DAT-1792081130689287

## Objective

Import deployed AWG dual-stack transport exactly and verify its real consumer data path.

## Ownership

The single consumer writer owns the paths declared in the portfolio task, including serialized contract mirrors and peer adapters. Independent reviewers are read-only.

## Execution

- [x] DAT-1792081224193869 Enforce deployment transport admission and activation MTU with boundary tests #feature !high @item:DAT-1792081130689287
- [ ] DAT-1792081224671303 Synchronize deployment contracts and verify real independent peer dual-stack payloads #feature !high @item:DAT-1792081130689287
- [x] DAT-1792081225208325 Exercise Android arm64 emulator native interop at both MTUs with three reconnects #feature !high @item:DAT-1792081130689287

## Verification

Run focused data/runtime-state/service/app Gradle tests, independent peer interop, native export/history tests, the producer federation gate, task validation and scoped real-native Android arm64 emulator acceptance. The only Android gate is emulator/native_core traffic at MTUs 1280 and 1420 with three reconnect cycles per MTU. Physical-device and Android VpnService TUN/no-leak checks are not required; Linux forwarding/no-leak verification belongs to the producer task.
