---
id: DAT-1793954543063965
title: Replace manual settings JSON codecs with generated serializers
kind: bug
status: review
area: data
priority: high
owner: Settings persistence maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: generated-settings-json
created: 2026-11-06
updated: 2026-11-06
status_detail: Local and native-size checks passed; the user waived further hosted CI waiting and requested final integration and push.
---

## Goal

Encode and decode settings backups with generated kotlinx.serialization serializers. Keep the current flat JSON contract and reject invalid known values before restore changes any stored data.

## Acceptance criteria

- Generated serializers own field names, types, defaults, and secret exclusions; remove per-field JSON readers and writers.
- Preserve format version 1, current key names, valid values, absent defaults, unknown-key tolerance, and existing restore validation.
- Reject malformed known values without a partial backup restore. Keep PIN and LAN authentication tokens out of both export and restore.
- Keep omitted disabled AWG settings off across repeated backups; reject incomplete inferred custom profiles before restore.
- Pass settings and backup tests, affected static analysis, source gates, and independent review. The user waived waiting for hosted CI on 2026-11-06; record its observed status without claiming a complete green run.
- Apply the user-approved native-size limit increase without changing historical measurements, gate coverage, or failure behavior.

## Ownership

The primary writer owns settings snapshots, their codec, the AWG restore mapper and tests, the backup restore regression test, and task/OpenSpec records in /private/tmp/ripdpi-settings-json. Review agents are read-only. No protobuf field, dependency version, or golden fixture change is planned.

The user approved a native-size limit increase on 2026-11-06 to complete this task. The native verifier exclusively owns `scripts/ci/native-size-baseline.json` and `docs/native/size-monitoring.md` in `/private/tmp/ripdpi-native-size-budget`. The primary writer owns the task and OpenSpec records in `/private/tmp/ripdpi-settings-json`. No verifier, CI route, golden, or bloat baseline change is planned.
