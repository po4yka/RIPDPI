## Context

The capture list and viewer routes currently render fixed sample data. `:core:pcap-export` already supplies private-directory capture metadata and a streaming PCAP reader.

## Goals / Non-Goals

- Goal: Show completed local captures and inspect packets in the selected file, including safe empty and error states.
- Non-goal: Add capture controls, export controls, packet dissection, or new native APIs.

## Decisions

- Pass the selected file name in the typed navigation route. Resolve it only under `PcapController.captureDirectory`, with canonical-path confinement and a `.pcap` suffix. This survives navigation restoration without accepting arbitrary paths.
- Load on `Dispatchers.IO` through `PcapReader.readOne()`. Show IP endpoints and protocol without inferring fragment ports, and format raw hex only for the expanded packet. Limit the first view to 2,000 packets and show a notice when more exist; this bounds UI memory for a 16 MiB capture.
- Treat native metadata listing failure as an error. An actual empty native result remains the empty state.
- Keep the existing Compose screen and local preview samples. Production routes no longer use those samples.

## Contracts and ownership

- Android `:app` routes, typed navigation, presentation, and tests change. `:core:pcap-export` controller error handling and its test change; its controller and reader remain the source of truth.
- No Rust crate, JNI, storage schema, wire contract, shared serialized registry, or migration changes.
- PCAP agent owns PCAP files and navigation edits in its worktree; coordinator owns integration and generated board.

## Risks / Trade-offs

- A rotated file can disappear between list and selection. The viewer shows an error instead of a false packet list.
- The first view is bounded. A notice states when the capture has more packets.

## Migration Plan

No migration is needed. Revert the app-only commit to roll back. Validate with targeted app unit tests, app Kotlin compile, Android lint and static analysis where available; device runtime remains a separate gate.
