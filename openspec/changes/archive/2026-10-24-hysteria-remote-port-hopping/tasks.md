# OUT-1792820412611017

## Objective

Carry valid imported Hysteria hopping policy into one authenticated native session and prove TCP/UDP continuity across local and remote port changes.

## Ownership

Kotlin lane owns runtime-state/data/service/engine-api model, import, storage, activation and JSON tests. Native lane owns ripdpi-hysteria2, affected ripdpi-relay-core config/builders/tests, peer fixtures and this task/spec. Root owns producer, combined tree, board integration and external delivery. Dependency pins, protobuf, locales and golden files are serialized outside these lanes.

## Execution

- [x] OUT-1792820587825821 Normalize standard Hysteria hopping imports and migrate persisted policy with boundary tests #feature !high @item:OUT-1792820412611017
- [x] OUT-1792820588684825 Preserve validated hopping policy through activation and direct or chain native JSON with contract tests #feature !high @item:OUT-1792820412611017
- [x] OUT-1792820589483909 Implement protected scheduled local and remote UDP rotation with lifecycle and Salamander tests #feature !high @item:OUT-1792820412611017
- [x] OUT-1792820590309126 Prove sustained TCP and UDP hopping with the pinned peer across address families and run combined gates #feature !high @item:OUT-1792820412611017

## Verification

Use targeted import, persistence, activation and wire tests; gated native tests and real-peer matrix; combined staticAnalysis, architecture-health, task-check, locked Cargo metadata and Android artifact checks. Record exact commands and results in verification.md. Hosted CI is required for delivery; production and physical-device acceptance are outside this change.
