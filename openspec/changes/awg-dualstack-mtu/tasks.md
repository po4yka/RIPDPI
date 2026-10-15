# DAT-1792081130689287

## Objective

Import deployed AWG dual-stack transport exactly and verify its real consumer data path.

## Ownership

The single consumer writer owns the paths declared in the portfolio task, including serialized contract mirrors and peer adapters. Independent reviewers are read-only.

## Execution

- [ ] DAT-1792081224193869 Enforce deployment transport admission and activation MTU with boundary tests #feature !high @item:DAT-1792081130689287
- [ ] DAT-1792081224671303 Synchronize deployment contracts and verify real independent peer dual-stack payloads #feature !high @item:DAT-1792081130689287
- [ ] DAT-1792081225208325 Exercise Android arm64 emulator native interop at both MTUs with three reconnects #feature !high @item:DAT-1792081130689287

## Verification

Run focused data/runtime-state/service Gradle tests, independent peer interop, task validation and scoped real-native Android arm64 emulator acceptance. No deployment or physical-device success is implied.
