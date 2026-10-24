# RTE-1792840535644306

## Objective

Keep active standalone AWG profile DNS inside that tunnel without bypassing effective Block or changing ordinary LAN routing.

## Ownership

The protocol runtime worker owns the exact source paths and adjacent tests listed in design.md. The integration owner serializes shared task-board changes and owns Android artifact and physical acceptance. No device or server mutation is delegated to the runtime worker.

## Execution

- [x] RTE-1792840610583608 Project frozen active AWG resolver authority and apply scoped native DNS egress without bypassing effective Block #bug !high @item:RTE-1792840535644306
- [x] RTE-1792840611038760 Verify codec validation, snapshot isolation, ordered policy boundaries and real upstream selection with complete affected gates #bug !high @item:RTE-1792840535644306
- [ ] RTE-1792840611484317 Integrate reviewed source and prove unchanged private DNS fixture traffic through rebuilt Android AWG artifact #bug !high @item:RTE-1792840535644306

## Verification

- Complete affected engine/service JVM suites, ktlint, and Detekt.
- Locked native proxy-config, config, and proxy-runtime tests plus all-target Clippy through build-gate; cargo fmt.
- Strict OpenSpec validation, taskctl validation, architecture/native contracts, and independent review before the normal commit hooks.
- Exact integrated commit hosted gates and rebuilt Android AWG IPv4/IPv6 DNS and payload acceptance, with default LAN rules and existing private fixture addresses retained.
