# Local Android ordinary release evidence

The Android release target has 11 DNS, IPv6, and kill-switch gates that are not
supplied by the dual-vantage manifest. The checked-in semantic verifier
evaluates those gates from seven action artifact bundles and fails closed when
any action, artifact, or source binding is missing or contradictory.

## Trust boundary

Ordinary PASS remains forbidden even after
`android_ordinary_semantic_oracles_v1` succeeds because the repository does not
yet contain a source-owned physical producer and attestation path. The verifier
validates the private bundle's source, APK, structural run/window/vantage
metadata, inventory, size, and digest bindings, then parses each action receipt,
classic PCAP, and route snapshot itself. In particular, the checker does not
accept:

- operator-authored PASS or counter fields;
- an arbitrary external collector or plugin;
- public hashes, a copied executable, or a self-authored evidence bundle as
  proof that a physical capture ran;
- JVM or host-only tests as physical packet-path release evidence.

This deliberately leaves no configurable verifier plugin or generic collector
execution path. The local contract suite is
`just test-android-ordinary-release-gates`; its deterministic fixtures exercise
the parser and are not physical release evidence.

## Private raw bundle

The canonical `android_ordinary_raw_bundle_v1` manifest must be an absolute,
single-link, mode-0600 regular file. It binds an exact clean source SHA, distinct
app and androidTest APK digests, a redacted SHA-256 run id, a fresh creation
timestamp, and an absolute current-user-owned mode-0700 artifact root.

The manifest contains the exact seven actions in source order: IPv4-only,
dual-stack, forced revoke, core fault, Wi-Fi/LTE switch, sleep/wake, and Android
always-on block. Each action binds its exact gate inventory, a unique redacted
SHA-256 correlation id, and one observation window. Each action has exactly
three unique mode-0600 single-link files under the artifact root:

- `action-receipt` from the `android-client` vantage;
- `packet-capture` from the `client-underlay` vantage;
- `route-snapshot` from the `android-client` vantage.

Every artifact entry repeats the action window and carries its exact byte size
and SHA-256 digest. Extra, partial, reordered, stale-metadata, mixed-correlation,
symlinked, hardlinked, noncanonical, or digest-tampered inputs fail closed. The
results output must be absolute, use a current-user-owned private parent, stay
outside the artifact root, and must not alias any input. In raw mode the output
parent must already exist; the producer never creates it. Manifest, APK, root,
and every listed artifact must be pinned before the output is reserved.

If malformed, noncanonical, incomplete, or digest-invalid evidence prevents
the producer from proving the output is disjoint from every raw artifact, it
exits with status 2 and does not touch an existing output. This is intentional:
content that resembles an older results document is not output provenance and
may itself be a raw artifact. Such a file is not accepted as release evidence
because the command failed and caller-authored PASS remains forbidden.

The `android_ordinary_action_receipt_v1` artifact contains only source-bound
events, per-probe outcomes, DNS observations, and private fixture endpoints. It
must not contain caller-authored `status`, `state`, `verdict`, `pass`, `success`,
or aggregate `count` fields. Every event and probe is ordered within the
manifest action window and must match the exact source-owned action inventory.

The packet artifact is bounded classic PCAP. The verifier parses Ethernet, raw
IP, Linux SLL, and Linux SLL2 frames, rejects truncated records, and locates one
action and one outcome marker derived from the action ID and correlation ID.
Across the complete manifest action window it rejects direct fixture traffic,
IPv6 traffic in IPv4-only mode, and every packet outside the approved marker or
tunnel endpoints. The action event must follow the action marker, all probes,
DNS observations, and route captures must follow that event, and every
observation must finish before the outcome marker. Sleep/wake additionally
binds the action marker between its sleep and wake timestamps. Actions that
require an established tunnel must contain parsed tunnel activity after the
event and before the outcome marker. Wi-Fi/LTE and sleep/wake actions enforce
the complete chain: transition snapshot and blocked probes, tunnel activity,
re-established route, post-tunnel connected probe, then outcome marker.
Tunnel traffic requires the endpoint and tunnel port to match on the same
packet direction.

The `android_ordinary_route_snapshot_v1` artifact carries raw `ip address`,
`ip route`, IPv6 route/address, resolver, connectivity, and secure-settings
outputs for source-owned phases. The verifier derives tunnel addresses and
default routes, transition lockdown state, re-establishment, and Android
Always-on settings from those outputs. Combined and IPv6-specific address views
must identify the declared VPN interface and expose the same global IPv6 set.
Addresses are parsed only from that interface's block, never from a following
underlay interface. An active VPN interface must also be UP and expose an IPv4
address.
Copied, cross-action, stale, partial, causally reordered, or contradictory
artifacts remain an explicit FAIL blocker even when the manifest is rehashed.

## Current local command

Generate the canonical no-ship result document from an exact clean commit:

```bash
python3 scripts/ci/produce_android_ordinary_gate_results.py \
  --raw-manifest /private/evidence/android-ordinary/raw-manifest.json \
  --app-apk /private/build/app-release.apk \
  --test-apk /private/build/app-release-androidTest.apk \
  --output /private/evidence/android-ordinary-results.json
```

Without all three raw inputs the command emits `RAW_EVIDENCE_REQUIRED`. With a
complete bundle, all seven semantic action oracles must pass before the command
records source-owned action proof digests and `semanticVerified: true`.
Nevertheless, it keeps the exact 11 public gate objects at structured FAIL,
sets `productionReady: false`, reports
`SOURCE_OWNED_PHYSICAL_PRODUCER_UNAVAILABLE`, and exits non-zero.
`SOURCE_OWNED_VERIFIER_AVAILABLE` is true for the checked-in parser, while
`SOURCE_OWNED_PHYSICAL_PRODUCER_AVAILABLE` remains false. Test fixtures, copied
artifacts, or a locally authored manifest are not release evidence. The result
is then combined with the disjoint dual-vantage evidence through:

```bash
python3 scripts/ci/check_dns_ipv6_killswitch_gates.py \
  --results /private/evidence/android-ordinary-results.json \
  --evidence-manifest /private/evidence/dual-vantage/manifest.json \
  --applies-to android-client-release \
  --expected-source-sha "$(git rev-parse HEAD)" \
  --expected-execution-kind local \
  --expected-execution-id "$LOCAL_EVIDENCE_RUN_ID" \
  --expected-execution-attempt 1
```

Do not turn a missing physical action, collector capability, or approved
exact-SHA artifact into PASS or N/A.
