## Context

Play release mapping at f1107748c contains the privilege settings and runtime. The native producer and assets are shared across all distributions.

## Goals / Non-Goals

- Give Play a base build and preserve the opt-in features in both extended distributions.
- Preserve the base VPN, proxy, diagnostics, and PCAP behavior.

## Decisions

- Select sources and assets by variant. Share one extended set between GitHub and F-Droid.
- Compile separate native capability sets with distinct outputs and verified prebuilt inputs.
- Validate capability at plan activation and project compatible data in Play.
- Verify the release artifact independent of source checks.

## Contracts and ownership

Gradle owns selection. Each executor enforces its capability limit. The integration owner serializes protobuf, locale, and release contract changes.

## Risks / Trade-offs

Old sessions can leave an extracted executable or firewall state. Exercise clean and failed process termination before rollout.

## Migration Plan

Reserve old setting tags. Handle old settings at activation and storage boundaries. Verify signed update behavior before Play publication.

Reserve the old opt-in tag because older versions wrote it before the access check completed. Extended builds store the new authorization flag only after a successful check. Existing users must enable the mode again.
