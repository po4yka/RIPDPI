# Active — RIPDPI

> `#status/todo` · `#status/doing` · `#status/review` tasks.


## todo

- [ ] #task Add cancellation + EnvironmentRunner short-circuit fixture tests for connectivity runners #repo/RIPDPI #area/testing #status/todo ⏫ [paperclip:POY-24]
  - Paperclip: POY-24 · assigned to: Test Automation Engineer
  
  Objective:
  Add two fixture tests in `ripdpi-monitor-engine` covering gates G3 (cancellation short-circuit) and G4 (`EnvironmentRunner::run` four-case behaviour) of POY-12.

  Context:
  The `support::collect_family_steps` helper now centralises the per-target cancellation check (`cancel.load(Ordering::Acquire)`); a regression that flips the polling order or drops the early-return `None` would silently leak partial probes into the final report. `EnvironmentRunner::run` retains a finalisation short-circuit when `transport == "none" && !vpn_service_was_active`, plus an unvalidated-network warn event; both are part of the user-visible contract.

  Owner:
  Test Automation Engineer.

  Subsystem:
  Native Rust / `ripdpi-monitor-engine`.

  Acceptance criteria:

  G3 — `support::collect_family_steps` cancellation:
  - New unit test using a stub `ConnectivityProbeFamily` (e.g., bound to `DnsTarget` or a synthetic target type) and a pre-set `AtomicBool` cancel flag.
  - Assert that with cancel pre-set, `collect_family_steps` returns `None` immediately and zero `run_probe` calls are made.
  - Assert that with cancel set after the first probe, `collect_family_steps` returns `None` after exactly one `run_probe` call (partial-stop semantics).

  G4 — `EnvironmentRunner::run` four cases:
  - Case (a) `network_snapshot is None` → `RunnerOutcome::Completed`, no warn event, no record_step.
  - Case (b) `transport == "none" && !vpn_service_was_active` → warn event "OS reports no network; aborting scan", `runtime.finish_with_report(...)` called, returns `RunnerOutcome::Finished`.
  - Case (c) `transport == "none" && vpn_service_was_active` → `Completed`, no abort, no warn for no-network branch.
  - Case (d) `!validated && !captive_portal` → warn event "OS reports unvalidated network; probe results may be unreliable", returns `Completed`.
  - Use the existing `ExecutionRuntime` test scaffold; assert against `shared.lock().events`.

  Required verification:
  - `cargo nextest run -p ripdpi-monitor-engine -E 'test(cancel) or test(environment)'` green.
  - Each case is an independent `#[test]` so a regression points at the specific failing branch.

  Required reviewers:
  - Senior Rust Native Engineer.
  - QA Lead.

  Risks:
  None — test-only.

  Definition of done:
  - Both tests added, green locally and in CI.
  - POY-12 gates G3 and G4 marked satisfied.

  Parent: POY-12.

- [ ] #task Add network-security-config with opportunistic domainEncryption #repo/RIPDPI #area/owned-stack-mode-with #status/todo 🔼 [paperclip:POY-127]
  - Paperclip: POY-127 · assigned to: unassigned
  - Parent: POY-51 (Epic - Owned-stack mode with Android 17 ECH)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, ech, android17
  - **source:** `TaskNotes/Tasks/Add network-security-config with opportunistic domainEncryption.md`
  - **epic:** Epic - Owned-stack mode with Android 17 ECH

  ## Summary

  Add `res/xml/network_security_config.xml` with
  `<domainEncryption mode="opportunistic"/>` as the base config, and point
  `AndroidManifest.xml` at it. Opportunistic unlocks platform ECH when both
  the library and DNS say yes.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §4A.

  ## Current status

  This task is partially landed in `/Users/po4yka/GitRep/RIPDPI`:

  - The manifest was already wired to `@xml/network_security_config`, and this
    pass adds `xml-v37` overlays so Android 17+ gets opportunistic
    `domainEncryption` without changing older-platform resources.
  - The same pass adds enabled per-domain config blocks for the current
    owned-stack probe hosts used by the first browser/remediation slice.
  - Still open: broader per-domain policy generation and Android 17 instrumented
    proof that ECH is attempted when DNS supplies a config.

  ## Acceptance criteria

  - [x] Config file exists with the base `domainEncryption` block on the
        Android-17+ resource path.
  - [x] Manifest references the config via
        `android:networkSecurityConfig="@xml/network_security_config"`.
  - [x] App still builds on minSdk targets below Android 17; the new
        attribute is ignored harmlessly on older versions.
  - [ ] Instrumented test on Android 17 confirms ECH is attempted when DNS
        surfaces an ECH config.

  ## Links

  - [[Epic - Owned-stack mode with Android 17 ECH]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Build CensorLab-style offline strategy-pack pipeline #repo/RIPDPI #area/privacy-preserving-strategy-learner #status/todo 🔼 [paperclip:POY-161]
  - Paperclip: POY-161 · assigned to: unassigned
  - Parent: POY-53 (Epic - Privacy-preserving strategy learner)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, tooling, offline
  - **source:** `TaskNotes/Tasks/Build CensorLab-style offline strategy-pack pipeline.md`
  - **epic:** Epic - Privacy-preserving strategy learner

  ## Summary

  Generate strategy packs in an emulator pipeline, not only from field
  failures. Gets us ahead of future stateful / ML-assisted censor behavior
  instead of reacting after users break.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §5 offline research track.

  ## Current status

  This task is partially landed in `/Users/po4yka/GitRep/RIPDPI`:

  - the repo-local offline analytics pipeline now emits
    `strategy-pack-catalog.candidate.json` during `publish` / `run-all`
  - generated catalogs conform to the current strategy-pack schema and preserve
    baseline metadata while appending staged `offline-*` packs derived from
    stable winner mappings
  - the sample-corpus test suite now covers candidate strategy-pack emission and
    pack-shape regression
  - still open: reproducible simulation seeds beyond field-derived archives,
    emulator calibration against known failures, and the final reviewed/signing
    workflow for generated packs

  ## Acceptance criteria

  - [ ] Pipeline is a standalone tool outside the app (runs in CI / on dev
        machines).
  - [ ] Reproducible seeds; same input produces the same candidate packs.
  - [x] Output conforms to the signed-strategy-pack format (see
        [[Add anti-rollback to strategy-pack updates]]).
  - [ ] Calibrated against a small set of known field failures before any
        generated pack ships.
  - [ ] Documented sim-to-field gap and how to measure it per release.

  ## Links

  - [[Epic - Privacy-preserving strategy learner]]
  - [[Add anti-rollback to strategy-pack updates]]
  - [[Sign host-pack manifests with app-trusted keys]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Classify IP_BLOCK_SUSPECT when all IPs fail #repo/RIPDPI #area/direct-mode-transport-policy #status/todo 🔼 [paperclip:POY-167]
  - Paperclip: POY-167 · assigned to: unassigned
  - Parent: POY-43 (Epic - Direct-mode transport policy and verdicts)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, classification
  - **source:** `TaskNotes/Tasks/Classify IP_BLOCK_SUSPECT when all IPs fail.md`
  - **epic:** Epic - Direct-mode transport policy and verdicts

  ## Summary

  When encrypted-DNS IPs and alternate address families all fail at connect
  time, classify the host as `IP_BLOCK_SUSPECT`. Do **not** brute-force
  transport tricks in this state.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §3 policy rule 3 and Phase
  2 classification.

  ## Acceptance criteria

  - [ ] Classification fires only when: DoH-provided IPs fail at SYN,
        alternate IP family fails at SYN, and no CDN variant succeeds within
        the attempt budget.
  - [ ] On `IP_BLOCK_SUSPECT`, the engine jumps straight to owned-stack arms
        (A10/A9) — no TLS family arms.
  - [x] False-positive guard: re-verify on the next flow before persisting,
        to avoid pinning on a transient network blip.

  ## Implementation note

  The false-positive guard landed on 2026-04-23: runtime `ALL_IPS_FAILED`
  learning now requires a second flow before it persists
  `NO_DIRECT_SOLUTION` / `IP_BLOCK_SUSPECT`. Full owned-stack arm gating and
  the stricter SYN-only classification budget are still open.

  ## Links

  - [[Epic - Direct-mode transport policy and verdicts]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Define Xray VPN provider architecture #repo/RIPDPI #area/xray-vpn-client-mode #status/todo ⏫ [paperclip:POY-180]
  - Paperclip: POY-180 · assigned to: unassigned
  - Parent: POY-61 (Epic - Xray VPN client mode)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **area:** android
  - **tags:** task, architecture, ripdpi, vpn, xray
  - **source:** `TaskNotes/Tasks/Define Xray VPN provider architecture.md`
  - **epic:** Epic - Xray VPN client mode

  ## Summary

  Define the local provider boundary for embedding Xray as a managed Android VPN
  client runtime.

  ## Context

  RIPDPI already has proxy, VPN tunnel, relay, WARP, native readiness, and typed
  telemetry concepts. Xray support should reuse those lifecycle patterns instead
  of adding a one-off service path.

  Plan reference: [[ripdpi-android-xray-vpn-client-plan-2026-04-24]].

  ## Acceptance criteria

  - [ ] Provider model names the first supported provider kinds and the state
        transitions shared by native RIPDPI and Xray paths.
  - [ ] Decision recorded for first tunnel topology: existing TUN-to-local-Xray
        inbound versus direct `libXray.SetTunFd`, with explicit tradeoffs.
  - [ ] Required Kotlin/Rust/Go wrapper module boundaries are listed with owners:
        `:core:service`, `:core:engine`, and any generated Xray adapter module.
  - [ ] Socket-protection, DNS-loop avoidance, telemetry, readiness, and stop
        semantics are described before implementation tasks start.
  - [ ] The architecture doc links back to the epic and avoids storing endpoints,
        credentials, or sample live configs.

  ## Notes

  Favor an adapter that hides libXray API churn from service and UI code.

  ## Links

  - [[Epic - Xray VPN client mode]]
  - [[ripdpi-android-xray-vpn-client-plan-2026-04-24]]

- [ ] #task Detect NO_TCP_FALLBACK app families #repo/RIPDPI #area/direct-mode-transport-policy #status/todo 🔼 [paperclip:POY-186]
  - Paperclip: POY-186 · assigned to: unassigned
  - Parent: POY-43 (Epic - Direct-mode transport policy and verdicts)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, transport, heuristic
  - **source:** `TaskNotes/Tasks/Detect NO_TCP_FALLBACK app families.md`
  - **epic:** Epic - Direct-mode transport policy and verdicts

  ## Summary

  If SOFT_DISABLE is applied and the app never retries on TCP and simply
  breaks, mark that app family `NO_TCP_FALLBACK` and don't apply
  soft-disable again. Protects us from breaking apps that hard-depend on
  QUIC.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §3 SOFT_DISABLE enforcement.

  ## Acceptance criteria

  - [x] Heuristic observes whether the app opens a TCP connection to the
        same host within a bounded window after a UDP/443 drop.
  - [ ] On no-retry, mark the app family `NO_TCP_FALLBACK` in a per-app
        memory.
  - [ ] The memory is invalidated on app update (package version change).
  - [x] Detection is conservative by default — false positives are better
        than breaking apps silently.
  - [x] Unit test covers: app retries (no mark), app never retries (mark),
        app partially retries (no mark).

  ## Implementation note

  As of 2026-04-23, RIPDPI already has a bounded-window `NO_TCP_FALLBACK`
  heuristic plus regression coverage and runtime behavior that stops
  reapplying UDP suppression once the signal is learned. What remains open is
  true per-app-family memory and invalidation on app package version change.

  ## Links

  - [[Implement QUIC soft-disable per tuple]]
  - [[Epic - Direct-mode transport policy and verdicts]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Gate DoQ on UDP-clean classification #repo/RIPDPI #area/encrypted-dns-and-https #status/todo 🔼 [paperclip:POY-194]
  - Paperclip: POY-194 · assigned to: unassigned
  - Parent: POY-44 (Epic - Encrypted DNS and HTTPS SVCB classifier)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, dns, doq
  - **source:** `TaskNotes/Tasks/Gate DoQ on UDP-clean classification.md`
  - **epic:** Epic - Encrypted DNS and HTTPS SVCB classifier

  ## Summary

  DoQ only as a fast path on networks where UDP/443 is already classified
  healthy — otherwise DoQ and QUIC censorship fail together.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §2 operational detail.

  ## Acceptance criteria

  - [x] DoQ is not offered until the transport policy engine has marked
        UDP/443 `udp_ok = true` for the current `NetProfile`.
  - [ ] DoQ failure demotes the network to `udp_suspect`, triggering DoH-only
        for the rest of the session.
  - [ ] No user-visible toggle — the policy is automatic and coarse-keyed by
        network profile.

  ## Implementation note

  As of 2026-04-23, RIPDPI now enforces the first half of this task on the
  live runtime path: if the active encrypted-DNS context is DoQ but the current
  authority has a direct-path capability that says UDP/443 is not clean, native
  hostname resolution automatically downgrades that authority back to DoH.
  What remains open is session-level demotion memory after a live DoQ failure.

  ## Links

  - [[Build DoH primary and secondary resolver pipeline]]
  - [[Epic - Encrypted DNS and HTTPS SVCB classifier]]
  - [[Epic - Direct-mode transport policy and verdicts]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Implement Phase 0 passive observation from last flow #repo/RIPDPI #area/direct-mode-diagnostic-state #status/todo 🔼 [paperclip:POY-199]
  - Paperclip: POY-199 · assigned to: unassigned
  - Parent: POY-42 (Epic - Direct-mode diagnostic state machine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, diagnostic
  - **source:** `TaskNotes/Tasks/Implement Phase 0 passive observation from last flow.md`
  - **epic:** Epic - Direct-mode diagnostic state machine

  ## Summary

  Before active probing, extract what we can from the last real failed flow:
  DNS outcome, TCP SYN/SYN-ACK, did failure happen before or after
  ClientHello, did UDP/443 fail while TCP to same host worked, did the
  response look like a blockpage.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] "Phase 0 — Passive
  observation first".

  ## Progress

  The full passive-observer struct is still not landed, but the repo-owned
  state machine no longer starts entirely from zero:

  - diagnostics finalization now consults the previously confirmed authority
    record before pinning a new direct-path verdict;
  - that stored authority prior is now used as a lightweight passive signal for
    confirmation/revalidation, especially when the current run only produced one
    active direct-path failure.

  Still open: emitting a typed `PassiveObservation` payload directly from live
  runtime failures and feeding that payload into Phase 1 / Phase 2 before active
  probing starts.

  ## Acceptance criteria

  - [ ] Passive observer runs when a flow fails; emits a typed
        `PassiveObservation` struct.
  - [ ] Blockpage detection uses a small heuristic set — TLS certificate
        mismatch, known RKN block HTML shapes, response sizes, common block
        patterns.
  - [ ] Phase 0 observation is consumed by Phase 1/Phase 2 classification
        instead of them probing from zero.
  - [ ] Zero added cost on success paths.

  ## Links

  - [[Epic - Direct-mode diagnostic state machine]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Implement QUIC soft-disable per tuple #repo/RIPDPI #area/direct-mode-transport-policy #status/todo ⏫ [paperclip:POY-200]
  - Paperclip: POY-200 · assigned to: unassigned
  - Parent: POY-43 (Epic - Direct-mode transport policy and verdicts)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, transport, quic
  - **source:** `TaskNotes/Tasks/Implement QUIC soft-disable per tuple.md`
  - **epic:** Epic - Direct-mode transport policy and verdicts

  ## Summary

  In transparent mode, drop outbound UDP/443 for the
  `(host, ip set, app family, network profile)` tuple when `quic_mode =
  SOFT_DISABLE`. Observe whether the app retries on TCP; if it does, win. If
  it doesn't, detect and remember (see `NO_TCP_FALLBACK`).

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §3 policy rule 1 and
  SOFT_DISABLE enforcement detail.

  ## Acceptance criteria

  - [x] UDP/443 drop is tuple-scoped — does not affect traffic outside the
        tuple.
  - [x] TCP/443 to the same host remains allowed.
  - [ ] Hard-disable tightens to the entire host for persistent cases.
  - [x] Observability: a counter per tuple for dropped UDP and subsequent
        TCP retries.

  ## Implementation note

  As of 2026-04-23, RIPDPI now enforces tuple-scoped UDP suppression on the
  runtime path and keeps TCP allowed for the same authority, with the existing
  direct-path learner observing dropped UDP and subsequent TCP retries. The
  latest enforcement slice also fixed the contradictory runtime behavior where
  `NO_TCP_FALLBACK` lifted UDP suppression but the adaptive UDP/QUIC hint layer
  still kept treating QUIC as broken for the same tuple. Remaining follow-up
  work is the host-wide `HARD_DISABLE` escalation policy plus the separate
  per-app-family invalidation story tracked under
  [[Detect NO_TCP_FALLBACK app families]].

  ## Links

  - [[Define TransportPolicy struct and per-host state]]
  - [[Detect NO_TCP_FALLBACK app families]]
  - [[Epic - Direct-mode transport policy and verdicts]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Implement direct-mode diagnostic orchestrator Phases 1-4 #repo/RIPDPI #area/direct-mode-diagnostic-state #status/todo ⏫ [paperclip:POY-202]
  - Paperclip: POY-202 · assigned to: unassigned
  - Parent: POY-42 (Epic - Direct-mode diagnostic state machine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, diagnostic
  - **source:** `TaskNotes/Tasks/Implement direct-mode diagnostic orchestrator Phases 1-4.md`
  - **epic:** Epic - Direct-mode diagnostic state machine

  ## Summary

  The glue. Runs Phase 1 (DNS classification) → Phase 2 (transport
  classification) → Phase 3 (ranked arm generation per class) → Phase 4
  (execute with early stop + one confirmation request). Respects the
  attempt budget.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] Phases 1–4 + candidate arms
  A0–A10.

  ## Progress

  The first persisted/user-visible slice is now landed:

  - subsystem outputs already produced by the DNS classifier, transport-policy
    verdicts, and transparent TLS-family work are now preserved through the
    stored diagnostics report instead of losing `strategyRecommendation` at the
    engine-wire boundary;
  - the summary layer now surfaces all three verdict families, including the
    positive `TRANSPARENT_WORKS` case;
  - Home audit can once again consume a persisted strategy recommendation when
    there is no reusable validated strategy-probe winner;
  - the repo-owned persistence path now honors `confirm_once` semantics:
    transparent / owned-stack results only pin after corroborating evidence or a
    matching prior, and negative results only pin after repeated active failure.

  Still open: the actual ranked-arm dispatcher, hard attempt-budget
  enforcement, and the full class-to-arm execution ladder from the plan.

  ## Acceptance criteria

  - [ ] Orchestrator delegates to subsystem epics, never reimplements them:
    - DNS → [[Epic - Encrypted DNS and HTTPS SVCB classifier]]
    - Transport policy → [[Epic - Direct-mode transport policy and verdicts]]
    - TLS family arms → [[Epic - Semantic TLS first-flight family engine]]
    - Arm ranking → [[Epic - Privacy-preserving strategy learner]]
    - Owned-stack arms → [[Epic - Owned-stack mode with Android 17 ECH]]
  - [ ] Per-class arm list matches the plan:
    - `DNS_BLOCK:           A1, A3, A4, A5, A6, A10, A9`
    - `SNI_TLS_SUSPECT:     A3, A5, A6, A7, A8, A10, A9`
    - `QUIC_BLOCK_SUSPECT:  A3, A4, A5, A6, A9`
    - `IP_BLOCK_SUSPECT:    A10, A9`
    - `UNKNOWN:             A1, A3, A4, A5, A9`
  - [x] Repo-owned persistence path requires `confirm_once`; pin only after
        confirmation.
  - [ ] Attempt budget hard-enforced (see [[Enforce diagnostic attempt budget]]).
  - [x] Produces one `DiagnosticResult` per run.

  ## Links

  - [[Enforce diagnostic attempt budget]]
  - [[Define DiagnosticResult and classification taxonomy]]
  - [[Implement Phase 0 passive observation from last flow]]
  - [[Implement Bayesian posterior arm scoring]]
  - [[Epic - Direct-mode diagnostic state machine]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Persist direct-mode policy with revalidation #repo/RIPDPI #area/direct-mode-diagnostic-state #status/todo 🔼 [paperclip:POY-219]
  - Paperclip: POY-219 · assigned to: unassigned
  - Parent: POY-42 (Epic - Direct-mode diagnostic state machine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, persistence
  - **source:** `TaskNotes/Tasks/Persist direct-mode policy with revalidation.md`
  - **epic:** Epic - Direct-mode diagnostic state machine

  ## Summary

  Phase 5 of the diagnostic. Policy is pinned with a TTL and invalidated on
  environmental change; after 3 consecutive failures re-runs the full
  diagnostic.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] "Phase 5 — Persistence and
  revalidation".

  ## Progress

  The repo-owned persistence path is now partially landed:

  - confirmed direct-path policy is stored with a 7-day TTL;
  - runtime ignores unconfirmed authority policy records instead of blindly
    replaying one-off diagnostics results;
  - three consecutive revalidation failures now retire the cached policy entry
    from runtime use;
  - `NO_DIRECT_SOLUTION` entries now age out when their cooldown expires instead
    of living forever in the injected direct-path capability set.

  Still open: ASN-aware invalidation, HTTPS/SVCB/ECH-specific invalidation, and
  the explicit shared atomic-write/revalidation surface across every policy
  store.

  ## Acceptance criteria

  - [x] TTL: 7 days default, configurable later if needed.
  - [ ] Invalidate on ASN change.
  - [x] Invalidate on access-type change (wifi ↔ cellular).
  - [x] Invalidate after 3 consecutive failures.
  - [ ] Invalidate when HTTPS/SVCB TTL expires or ECH capability changes.
  - [ ] Atomic write (shares path with
        [[Make cache snapshot writes atomic]]).
  - [ ] Phase 6 rotation triggers only within the same policy entry — does
        not count against the TTL.

  ## Links

  - [[Implement direct-mode diagnostic orchestrator Phases 1-4]]
  - [[Rotate successful family through variant neighborhood]]
  - [[Make cache snapshot writes atomic]]
  - [[Epic - Direct-mode diagnostic state machine]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Replace generic relay suggestion with transport-specific remediation ladder #repo/RIPDPI #area/direct-mode-diagnostic-state #status/todo ⏫ [paperclip:POY-228]
  - Paperclip: POY-228 · assigned to: unassigned
  - Parent: POY-42 (Epic - Direct-mode diagnostic state machine)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-22
  - **dateModified:** 2026-04-23
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, relay, ux
  - **source:** `TaskNotes/Tasks/Replace generic relay suggestion with transport-specific remediation ladder.md`
  - **epic:** Epic - Direct-mode diagnostic state machine

  ## Summary

  Replace the current one-size-fits-all "Russian mobile relay preset"
  recommendation with a remediation ladder that chooses between owned-stack,
  browser-camouflage relay, QUIC-heavy relay, or "no useful relay hint" based on
  direct-mode verdicts plus saved capability evidence.

  ## Context

  RIPDPI already has relay suggestion plumbing in `ConfigRelaySupport.kt` and
  capability-aware preset reasons in `RelayPresetCatalog.kt`, but the runtime
  message is still generic: whitelist pressure maps to one Russian mobile relay
  preset.

  Today's research notes make that too coarse:

  - [[whitelist-oriented-censorship-resilience-2026]] shows whitelist pressure is
    an escalation ladder, not one binary state.
  - [[naiveproxy-vs-hysteria2-russia-2026]] separates browser-camouflage fallback
    from QUIC/system-wide fallback.
  - [[orthogonal-fallback-portfolio-2026]] argues these branches should not be
    collapsed into one "relay mode".

  The user-facing action after a failed direct-mode run should therefore be:
  "open in RIPDPI browser", "prefer NaiveProxy", "prefer Hysteria2/TUIC/MASQUE",
  or "direct path unavailable and no reliable relay hint yet" rather than one
  generic fallback sentence.

  ## Current landing status

  As of 2026-04-23, the first product slice is landed in
  `/Users/po4yka/GitRep/RIPDPI`:

  - Diagnostics and Home now project typed direct-mode verdict metadata into a
    shared transport-remediation selector.
  - The remediation ladder can now branch to owned-stack browser,
    browser-camouflage relay, QUIC-heavy relay, or "no reliable relay hint"
    instead of collapsing every negative direct-mode result into a generic relay
    fallback.
  - Home also consumes saved authority capability evidence when choosing between
    browser-camouflage and QUIC-heavy relay guidance.
  - Mode Editor is now wired as the relay handoff action from both surfaces.

  The remaining work is config-side unification and taxonomy completion:
  `ConfigRelaySupport.kt` still uses its older preset-suggestion heuristic rather
  than the same selector, and the distinct `DOMESTIC_DIRECT_RELAY_FOREIGN` branch
  is still implicit in preset heuristics instead of being surfaced as its own
  remediation class.

  ## Acceptance criteria

  - [x] A shared remediation model maps `DiagnosticResult + TransportClass +
        saved capability evidence` to a specific action class instead of one
        generic relay suggestion.
  - [ ] The ladder distinguishes at least:
        `OWNED_STACK_ACTION`, `BROWSER_FALLBACK`, `QUIC_FALLBACK`,
        `DOMESTIC_DIRECT_RELAY_FOREIGN`, and `NO_RELIABLE_RELAY_HINT`.
  - [ ] Diagnostics UI and config relay suggestions use the same remediation
        model, so users do not see contradictory recommendations.
  - [ ] When saved evidence shows `quicUsable == false` or HTTPS proxying is the
        safer path, the recommendation prefers a browser-camouflage branch such
        as NaiveProxy over QUIC-heavy presets.
  - [x] When saved evidence shows QUIC/UDP relay paths are healthy, the
        recommendation prefers the QUIC-heavy branch rather than the generic
        Russian mobile relay fallback.
  - [x] Focused unit/UI tests cover the owned-stack branch, browser fallback,
        QUIC fallback, and no-supported-relay-hint branch.

  ## Notes

  Keep the existing three direct-mode result classes. This task is about
  remediation above the verdict, not about exploding `DiagnosticResult` itself.

  ## Links

  - [[Epic - Direct-mode diagnostic state machine]]
  - [[Report OWNED_STACK_ONLY verdict from diagnostic]]
  - [[naiveproxy-vs-hysteria2-russia-2026]]
  - [[orthogonal-fallback-portfolio-2026]]
  - [[whitelist-oriented-censorship-resilience-2026]]

- [ ] #task Report OWNED_STACK_ONLY verdict from diagnostic #repo/RIPDPI #area/owned-stack-mode-with #status/todo 🔼 [paperclip:POY-229]
  - Paperclip: POY-229 · assigned to: unassigned
  - Parent: POY-51 (Epic - Owned-stack mode with Android 17 ECH)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, diagnostic
  - **source:** `TaskNotes/Tasks/Report OWNED_STACK_ONLY verdict from diagnostic.md`
  - **epic:** Epic - Owned-stack mode with Android 17 ECH

  ## Summary

  When transparent arms (A3–A8) all fail but an owned-stack arm (A9/A10)
  works, the diagnostic returns `OWNED_STACK_ONLY`. Surface that as a real
  verdict, not a failure — "open this host inside the RIPDPI browser" is a
  legitimate outcome.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §4 and
  `classify_success(arm)` in Phase 4.

  ## Current status

  This task is partially landed in `/Users/po4yka/GitRep/RIPDPI`:

  - The diagnostics UI now treats `OWNED_STACK_ONLY` as a real outcome and
    offers a direct action to open the authority in the RIPDPI browser.
  - Session-row projections carry the launch URL and owned-stack-only flag so
    remediation can be derived from persisted diagnostic output.
  - Remaining work still belongs to the direct-mode state-machine / policy path:
    owning the final classifier arm mapping, persisting the verdict as a
    reusable transport-policy outcome for future flows, and returning a
    structured transparent-mode-not-supported result to third-party traffic.

  ## Acceptance criteria

  - [ ] Diagnostic's `classify_success` returns `OWNED_STACK_ONLY` when the
        winning arm is A9 or A10 and no transparent arm succeeded.
  - [x] UI/diagnostics surface: "Transparent mode: no / Owned-stack mode:
        yes" with a direct action to open the URL in the in-app browser.
  - [ ] Persisted policy sets `outcome = OWNED_STACK_ONLY` on the
        `TransportPolicy` so subsequent flows skip transparent attempts.
  - [ ] Third-party apps hitting this host in transparent mode get a
        structured "not supported in transparent mode" result, not a silent
        failure.

  ## Links

  - [[Implement direct-mode diagnostic orchestrator Phases 1-4]]
  - [[Epic - Owned-stack mode with Android 17 ECH]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Spike signed route-pack schema for direct-vs-relay policy #repo/RIPDPI #area/control-plane-hardening #status/todo 🔼 [paperclip:POY-243]
  - Paperclip: POY-243 · assigned to: unassigned
  - Parent: POY-41 (Epic - Control-plane hardening)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-22
  - **dateModified:** 2026-04-22
  - **area:** android
  - **tags:** task, spike, ripdpi, control-plane, policy
  - **source:** `TaskNotes/Tasks/Spike signed route-pack schema for direct-vs-relay policy.md`
  - **epic:** Epic - Control-plane hardening

  ## Summary

  Decide whether RIPDPI should add a signed route-pack layer above host packs and
  strategy packs to carry per-destination and per-app direct-vs-relay policy
  hints for whitelist-sensitive networks.

  ## Context

  Today's control-plane research points to a gap in the current RIPDPI model.

  - [[sing-box-antizapret-control-plane-2026]] frames the hard problem as feed →
    rule-set → runtime policy, with integrity, cadence, and schema-drift concerns.
  - [[whitelist-oriented-censorship-resilience-2026]] shows that under stronger
    allowlist pressure the app needs more than "host present in pack" decisions;
    it needs structured policy hints about which lane should stay direct, which
    should move to relay, and which should surface owned-stack-only guidance.

  RIPDPI already has signed strategy packs and a separate host-pack catalog, but
  neither is clearly the right carrier for destination-class policy such as
  "domestic direct", "browser fallback preferred", or "owned-stack only".

  ## Acceptance criteria

  - [ ] The spike decides whether route intent belongs in:
        existing host packs, existing strategy packs, or a new signed route-pack
        artifact.
  - [ ] The output defines a signed manifest shape with at least `sequence`,
        `issued_at`, `channel`, and compatibility/version fields.
  - [ ] The output compares JSON vs compiled/binary runtime formats for the
        policy artifact and records the chosen direction with tradeoffs.
  - [ ] The output defines refresh cadence, anti-rollback expectations, and
        schema-drift handling behavior.
  - [ ] The output includes one migration example for whitelist-sensitive
        destinations or apps, including a domestic-direct exception path.
  - [ ] The output states explicitly what must *not* go into this pack class
        (for example secrets or operator-private material).

  ## Notes

  This is a schema and control-plane spike, not an implementation task. If the
  answer is "extend host packs", document why a third pack type is not worth the
  operational cost.

  ## Links

  - [[Epic - Control-plane hardening]]
  - [[Sign host-pack manifests with app-trusted keys]]
  - [[Add anti-rollback to strategy-pack updates]]
  - [[sing-box-antizapret-control-plane-2026]]
  - [[whitelist-oriented-censorship-resilience-2026]]

- [ ] #task Surface NO_DIRECT_SOLUTION verdict honestly #repo/RIPDPI #area/direct-mode-transport-policy #status/todo 🔼 [paperclip:POY-247]
  - Paperclip: POY-247 · assigned to: unassigned
  - Parent: POY-43 (Epic - Direct-mode transport policy and verdicts)
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, ux
  - **source:** `TaskNotes/Tasks/Surface NO_DIRECT_SOLUTION verdict honestly.md`
  - **epic:** Epic - Direct-mode transport policy and verdicts

  ## Summary

  When the diagnostic exhausts its arms without a stable success, return
  `NO_DIRECT_SOLUTION` rather than keep burning attempts. Surface this to
  the user as a real verdict, not an error.

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §3 rule 5 and "Phase 4" end
  state.

  ## Acceptance criteria

  - [x] Diagnostic returns the verdict with a structured reason code
        (`IP_BLOCKED`, `TLS_BLOCKED_NO_ARMS_WORKED`, `DNS_BLOCKED_NO_ECH`,
        etc.).
  - [x] UI/diagnostics surface displays the verdict + reason; does not
        pretend to keep trying.
  - [x] A cooldown prevents immediately re-running the full diagnostic for
        the same host on the same network profile.
  - [ ] Persisted verdict is subject to the Phase 5 revalidation rules
        (ASN change, access-type change, etc.).

  ## Implementation note

  The first honest-verdict slice landed on 2026-04-23: diagnostics now keep
  distinct TLS, QUIC, and likely-IP-block `NO_DIRECT_SOLUTION` causes, and
  summary text surfaces the verdict reason instead of pretending the scan
  should keep trying. Full Phase 5 persistence / revalidation behavior is
  still open.

  ## Links

  - [[Persist direct-mode policy with revalidation]]
  - [[Epic - Direct-mode transport policy and verdicts]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]


## doing

- [ ] #task Finish native Rust verification for current connectivity/platform diff #repo/RIPDPI #area/rust-native #status/doing ⏫ [paperclip:POY-8]
  - Paperclip: POY-8 · assigned to: Senior Rust Native Engineer
  
  Objective:
  Own implementation-level validation and any small corrective edits for the current native/rust connectivity/platform diff.

  Context:
  Parent POY-3 found changes in native/rust/Cargo.lock, ripdpi-android Cargo.toml/src/ffi.rs, ripdpi-desync-runtime platform traits and test support, ripdpi-diagnostics-probes facade, ripdpi-monitor-engine connectivity runner split, monitor-engine dependencies, and ripdpi-proxy-runtime desync platform implementation.

  Owner:
  Senior Rust Native Engineer.

  Priority:
  High.

  Parent issue or goal linkage:
  Parent: POY-3. Goal: RIPDPI governance and release-readiness baseline.

  Acceptance criteria:
  - Ensure the current diff compiles and preserves existing behavior for connectivity runner stages.
  - Ensure dependency removals from ripdpi-android and ripdpi-monitor-engine are justified by actual imports, not accidental under-linking.
  - Ensure TcpDesyncPlatform trait decomposition remains compatible with registry/dispatch wrapper usage and test support.
  - Make only minimal corrective edits if verification exposes issues; do not broaden scope.
  - Post a handoff summary listing changed files, verification run, failures, and residual risk.

  Expected artifact:
  Verified native diff or minimal patch plus Paperclip handoff summary.

  Constraints:
  No live network experiments. No Android signing/release changes. Preserve unrelated working tree changes.

  Risks:
  Compile-only success may miss diagnostics behavior drift; coordinate with Network Protocol and QA for behavior coverage.

  Required verification:
  At minimum: cargo fmt check for affected workspace, cargo test -p ripdpi-monitor-engine, cargo test -p ripdpi-desync-runtime, cargo test -p ripdpi-diagnostics-probes, and a compile check for ripdpi-android. If JNI/native artifact boundaries are affected, request Android native build verification from Build/Gradle.

  Required reviewers:
  Principal Android/Rust Architect for architecture boundary approval; Security/AppSec for JNI/native networking/privacy risk; CTO before merge-readiness.

  Definition of done:
  Implementation verification evidence is posted, any corrective patch is scoped, and all required review gates are linked.

- [ ] #task Network protocol review: diagnostics connectivity behavior after runner refactor #repo/RIPDPI #area/rust-native #status/doing ⏫ [paperclip:POY-9]
  - Paperclip: POY-9 · assigned to: Senior Network Protocol Engineer
  
  Objective:
  Review the current connectivity runner refactor for DNS/TCP/QUIC/service/circumvention behavior equivalence and diagnostics semantics.

  Context:
  Parent POY-3 found monitor-engine connectivity.rs split into environment, dns, web, quic, tcp, service, circumvention, throughput, telegram, and support modules. The new support trait centralizes target collection, messages, latest target labels, artifact source mapping, and cancellation checks.

  Owner:
  Senior Network Protocol Engineer.

  Priority:
  High.

  Parent issue or goal linkage:
  Parent: POY-3. Goal: RIPDPI governance and release-readiness baseline.

  Acceptance criteria:
  - Compare old runner macro behavior to new per-stage modules for DNS, web reachability, QUIC, TCP, service, circumvention, throughput, telegram, and environment.
  - Confirm phase names, target labels, artifact sources, tls_verifier usage, whitelist_sni usage, path_mode usage, and cancellation behavior are preserved.
  - Identify any DNS/proxy/VPN/desync behavior risk requiring Security/AppSec or QA escalation.
  - Define targeted network-behavior regression expectations without running live network experiments.

  Expected artifact:
  Paperclip review comment with approve/request-changes/block decision and concrete test matrix recommendations.

  Constraints:
  Do not run live network experiments. Do not implement code unless explicitly assigned. Work from current local repository diff.

  Risks:
  Small runner-label or artifact-source drift can break diagnostics summaries, exports, or user-visible audit interpretation.

  Required verification:
  Read diff and relevant local files. Recommend targeted cargo tests or fixture/golden checks; if no existing test covers behavior equivalence, call that out.

  Required reviewers:
  Security/AppSec for diagnostics/privacy implications; QA Lead for regression matrix; CTO for final gate.

  Definition of done:
  Network behavior review is posted with explicit pass/fail and required verification before merge-readiness.

- [ ] #task Build verification: Android native packaging impact of Rust dependency-surface changes #repo/RIPDPI #area/android #status/doing 🔼 [paperclip:POY-10]
  - Paperclip: POY-10 · assigned to: Senior Build Gradle CI Engineer
  
  Objective:
  Verify build and Android native packaging risk from current Rust dependency-surface changes.

  Context:
  Parent POY-3 found Cargo.lock changes and removed direct dependencies from ripdpi-android and ripdpi-monitor-engine, while ripdpi-android remains the Android cdylib/JNI facade. Native Android artifacts are built through Gradle :core:engine Rust native tasks.

  Owner:
  Senior Build / Gradle / CI Engineer.

  Priority:
  Medium.

  Parent issue or goal linkage:
  Parent: POY-3. Goal: RIPDPI governance and release-readiness baseline.

  Acceptance criteria:
  - Confirm removed direct dependencies do not affect Android cdylib linking, generated jniLibs, or root-helper/tunnel artifacts.
  - Identify the smallest Android/Gradle verification needed for this diff and whether local ABI narrowing is acceptable for initial validation.
  - Confirm no Gradle convention, ABI, SDK, NDK, signing, or release behavior was changed by this diff.
  - Escalate if Android native packaging needs broader CI/release verification.

  Expected artifact:
  Paperclip comment with required build checks, any failures observed, and merge-readiness recommendation.

  Constraints:
  Do not change signing configuration. Do not publish artifacts. Avoid broad builds unless necessary; prefer the smallest relevant verification.

  Risks:
  Cargo dependency pruning can compile on host but fail Android cdylib packaging or ABI-specific native builds.

  Required verification:
  At minimum recommend/perform the smallest relevant Gradle native build check, such as :core:engine:buildRustNativeLibs with local ABI narrowing if appropriate, plus note whether full ABI CI coverage remains required.

  Required reviewers:
  Senior Rust Native Engineer for native crate correctness; CTO for release-readiness gate.

  Definition of done:
  Build/packaging verification requirements and result are posted, with any required CI follow-up made explicit.

- [ ] #task Align diagnostics privacy and export copy #repo/RIPDPI #area/android #status/doing 🔼 [paperclip:POY-15]
  - Paperclip: POY-15 · assigned to: Documentation Engineer
  - Blocked by: POY-13, POY-14
  
  Objective:
  Align RIPDPI user-facing privacy and diagnostics copy with the approved export/PCAP boundary after CTO and AppSec decisions.

  Context:
  POY-6 produced the acceptance gate and found copy gaps: README privacy promises mention no full packet captures while app/archive paths can expose PCAP; share/save archive strings say raw report data stays intact but do not plainly enumerate exclusions such as payloads, credentials, TLS secrets, or PCAP conditions; support bundle copy says app-visible logcat and recent debug information without enough user-facing detail.

  Owner:
  Documentation / UX Engineer.

  User story:
  As a non-technical RIPDPI user, I want export and privacy copy to say what is collected, what is not collected, and when advanced packet capture is included, so that I can decide whether to share diagnostics.

  Affected surface:
  README.md, README-ru.md if maintained in parallel, data transparency screen, Diagnostics share/save archive cards, support bundle copy, Home PCAP toggle/helper, Diagnostics packet-capture card.

  Acceptance criteria:
  1. User story: As a non-technical RIPDPI user, I want export and privacy copy to say what is collected, what is not collected, and when advanced packet capture is included, so that I can decide whether to share diagnostics.
  2. Observable behavior: A user can read the affected UI/docs and see: what the export contains, what it excludes, whether PCAP is included, whether logcat is app-scoped, retention/deletion expectations, and that sharing happens only through explicit user action.
  3. Success metric or test name: Updated copy is covered or explicitly verified by `RipDpiScreenCatalogScreenshotTest.diagnosticsShareScreen`, `AdvancedSettingsScreenCharacterizationTest.diagnostics section renders`, `DiagnosticsScreenTest` archive/share assertions, or equivalent screenshot/UI test names chosen by QA.
  4. Privacy implication: Yes. This changes privacy disclosure copy and must not begin until AppSec approval in POY-14 and CTO boundary in POY-13 are available.
  5. Rollback note: Copy-only changes are reversible by reverting strings/docs. If the approved decision requires migration or retained-file cleanup copy, document the user-visible fallback state.
  6. Explicit non-goals: This issue does not implement archive behavior. This issue does not alter diagnostic collection scope. This issue does not approve PCAP recording.

  Privacy implication:
  High. User-facing privacy claims must match implementation and AppSec-approved wording.

  Required verification:
  Diff of actual copy changes; AppSec written approval; QA confirmation of screenshot/UI coverage or explicitly unchanged baselines.

  Required reviewers:
  Product Manager, Security/AppSec Engineer, QA Lead, Senior Android Engineer if in-app strings change.

  Rollback note:
  Revert copy/docs if AppSec or QA rejects the framing; no data migration unless POY-13/POY-14 requires retained-file cleanup language.

  Non-goals:
  - No Kotlin/Rust behavior changes.
  - No new diagnostics targets or telemetry fields.
  - No release approval.

  Definition of done:
  Approved copy is committed to repo files, screenshots/tests are updated or confirmed unchanged, and AppSec/QA review comments are present.

- [ ] #task Add desync trait-split parity tests for TcpDesyncPlatform sub-traits #repo/RIPDPI #area/rust-native #status/doing ⏫ [paperclip:POY-17]
  - Paperclip: POY-17 · assigned to: Senior Network Protocol Engineer
  
  Owner: Senior Network Protocol Engineer (with QA Lead review).
  Parent: POY-4.

  Context
  ripdpi-desync-runtime split TcpDesyncPlatform into five capability traits (TcpPlatformCapabilities, TcpSocketOptions, TcpFakeSender, TcpPayloadSender, TcpFragmentSender) plus a blanket impl. Without dedicated tests, future trait splits or impl drift could silently break runtime callers.

  Acceptance criteria
  - Compile-time guard `fn _assert_impl<T: TcpDesyncPlatform>() {}` covering existing call sites.
  - Unit tests on TestTcpDesyncPlatform exercising each of the five sub-traits independently.
  - `cargo nextest run -p ripdpi-desync-runtime` green.
  - No live network; no payload capture.

  Definition of done
  PR merged with green tests; QA Lead acknowledges parity coverage in POY-4.

- [ ] #task Connectivity runner behavioral parity snapshot test #repo/RIPDPI #area/testing #status/doing ⏫ [paperclip:POY-18]
  - Paperclip: POY-18 · assigned to: Test Automation Engineer
  
  Owner: Test Automation Engineer (commissioned by QA Lead).
  Parent: POY-4.

  Context
  ripdpi-monitor-engine extracted connectivity.rs into 10 submodules (environment, dns, web, quic, tcp, service, circumvention, throughput, telegram, support). The refactor must be behavior-preserving across stage IDs, phase strings, total_steps, RunnerOutcome, and event order.

  Acceptance criteria
  - Snapshot/golden test that runs a fixture ExecutionPlan through every connectivity stage and captures stage IDs, phase strings, total_steps, RunnerOutcome, and the ordered list of recorded events.
  - Snapshot is committed and locked.
  - Bundled fixtures only; no live network.

  Definition of done
  PR merged with green snapshot test; reviewed by Senior Network Protocol Engineer; QA Lead acknowledges in POY-4.

- [ ] #task CI: build ripdpi-diagnostics-probes with both compat-facade on and off #repo/RIPDPI #area/ci #status/doing 🔼 [paperclip:POY-19]
  - Paperclip: POY-19 · assigned to: Senior Build Gradle CI Engineer
  
  Owner: Senior Build/Gradle/CI Engineer.
  Parent: POY-4.

  Context
  ripdpi-diagnostics-probes moved its historic root re-exports under `compat::*`, gated by a default `compat-facade` feature. Without explicit CI coverage, a future change could break the no-feature shape silently.

  Acceptance criteria
  - CI runs `cargo check -p ripdpi-diagnostics-probes --no-default-features` and `cargo check -p ripdpi-diagnostics-probes --features compat-facade`; both must be green.
  - CI-only; no live network.

  Definition of done
  PR merged; both jobs green on a sample PR.

- [ ] #task JNI symbol diff guard for libripdpi.so #repo/RIPDPI #area/rust-native #status/doing ⏫ [paperclip:POY-20]
  - Paperclip: POY-20 · assigned to: Senior Rust Native Engineer
  
  Owner: Senior Rust Native Engineer (with Senior Android Engineer review).
  Parent: POY-4.

  Context
  ripdpi-android Cargo.toml dropped direct deps on ripdpi-desync, ripdpi-packets, and ripdpi-session. If any JNI export referenced those crates through cfg-gated paths, a release build could silently lose a symbol that Kotlin loads via System.loadLibrary, producing UnsatisfiedLinkError at runtime.

  Acceptance criteria
  - Checked-in expected JNI export list for libripdpi.so (release, per ABI).
  - CI step diffs actual symbol list (nm/llvm-nm/objdump) against expected list and fails on any drop or unintended addition.
  - Regen procedure documented in build/CI docs.
  - Read-only inspection of release artifact; no signing-config changes.

  Definition of done
  PR merged; symbol-diff job green; Senior Android Engineer signs off that all Kotlin-loaded symbols are present.

- [ ] #task Audit and migrate in-workspace ripdpi-diagnostics-probes consumers off the compat facade #repo/RIPDPI #area/rust-native #status/doing 🔼 [paperclip:POY-21]
  - Paperclip: POY-21 · assigned to: Senior Rust Native Engineer
  
  Owner: Senior Rust Native Engineer.
  Parent: POY-4.

  Context
  ripdpi-diagnostics-probes is now a compat facade for external consumers. In-workspace callers should depend directly on the narrower ripdpi-diagnostics-* crates so the compat-facade feature can eventually be marked external-only.

  Acceptance criteria
  - Inventory every in-workspace caller of `ripdpi_diagnostics_probes::*` (now `compat::*`).
  - Migrate each caller to the appropriate narrow ripdpi-diagnostics-* crate.
  - `rg "ripdpi-diagnostics-probes" native/rust/crates -l` returns only the crate itself plus documented external boundary.
  - All affected crates compile and tests pass.
  - No behavioral change.

  Definition of done
  PR merged; QA Lead confirms inventory in POY-4 closure note.

- [ ] #task CI: cargo-tree assertion that monitor-engine no longer pulls ripdpi-runtime-api or ripdpi-diagnostics-pcap #repo/RIPDPI #area/ci #status/doing 🔼 [paperclip:POY-22]
  - Paperclip: POY-22 · assigned to: Senior Build Gradle CI Engineer
  
  Owner: Senior Build/Gradle/CI Engineer.
  Parent: POY-4.

  Context
  ripdpi-monitor-engine dropped direct deps on ripdpi-runtime-api and ripdpi-diagnostics-pcap. We want a CI guard so a future workspace edit cannot reintroduce them transitively without explicit review.

  Acceptance criteria
  - CI step runs `cargo tree -p ripdpi-monitor-engine -i ripdpi-runtime-api` and `cargo tree -p ripdpi-monitor-engine -i ripdpi-diagnostics-pcap`, expects no matching crate.
  - Documented update procedure if either is intentionally reintroduced.
  - CI-only; no live network.

  Definition of done
  PR merged; guard job green on main.

- [ ] #task Add phase/artifact-source byte-identity regression test for connectivity stage runners #repo/RIPDPI #area/testing #status/doing ⏫ [paperclip:POY-23]
  - Paperclip: POY-23 · assigned to: Test Automation Engineer
  
  Objective:
  Add a regression test in `ripdpi-monitor-engine` asserting that every `ExecutionStageRunner::phase()` and `ConnectivityProbeFamily::ARTIFACT_SOURCE` constant is byte-identical to the pre-split list. This is gate G1 of POY-12.

  Context:
  POY-7 noted that the `Web` stage publishes phase string `reachability` (not `web`). The connectivity decomposition (`c795e066..af66236c`) preserves all phase/artifact strings, but there is no test that locks them in. A future contributor renaming a runner could silently desync the phase string and break downstream telemetry consumers.

  Owner:
  Test Automation Engineer.

  Subsystem:
  Native Rust / `ripdpi-monitor-engine`.

  Acceptance criteria:
  - Test asserts the following pairs are byte-identical to a frozen const slice in the test module:
    - dns / dns_integrity
    - tcp / tcp_fat_header
    - quic / quic_reachability
    - reachability / domain_reachability   (web runner — note phase is `reachability`, not `web`)
    - throughput / throughput_window
    - circumvention / circumvention_reachability
    - service / service_reachability
    - telegram / telegram
    - environment / network_environment
  - Test lives in `native/rust/crates/ripdpi-monitor-engine/src/engine/runners` (sibling of the `connectivity/` module) or in `tests/` if accessing `pub(super)` symbols requires it.
  - Test references the runner constants directly (no string duplication beyond the frozen list), so a rename forces an explicit fixture update.

  Required verification:
  - `cargo nextest run -p ripdpi-monitor-engine -E 'test(phase) or test(artifact_source)'` green.
  - Mutating any one phase or artifact-source string in source must make the test fail.

  Required reviewers:
  - Senior Rust Native Engineer.
  - QA Lead (gate sign-off).

  Risks:
  None. Pure regression net for a string-identity invariant.

  Definition of done:
  - Test added, green locally and in CI.
  - POY-12 gate G1 marked satisfied.

  Parent: POY-12.

- [ ] #task Extend contract_fixtures with connectivity scan-report golden covering cancellation + partial-results #repo/RIPDPI #area/testing #status/doing ⏫ [paperclip:POY-26]
  - Paperclip: POY-26 · assigned to: Test Automation Engineer
  
  Objective:
  Extend `native/rust/crates/ripdpi-monitor-engine/tests/contract_fixtures.rs` with a golden test that exercises a connectivity scan running through the parallel `Dns`/`Tcp`/`Quic` group, cancels mid-`Quic`, and asserts the resulting `ScanReport` JSON shape is byte-identical to a checked-in fixture. This is gate G6 of POY-12.

  Context:
  The connectivity-runner decomposition routes every per-stage runner through the new `support::collect_family_steps` helper plus a single `EnvironmentRunner` short-circuit. There is currently no end-to-end fixture that locks in the post-decomposition `ScanReport` for the cancellation/partial-results path. A regression in stage ordering, partial-stage emission, or `connectivity_summary` aggregation would not be caught by per-runner unit tests alone.

  Owner:
  Test Automation Engineer.

  Subsystem:
  Native Rust / `ripdpi-monitor-engine` (test-only; no production code change).

  Acceptance criteria:
  - Add a `#[test]` in `tests/contract_fixtures.rs` (or a new `tests/connectivity_report_golden.rs`) that:
    1. Builds an `ExecutionPlan` with bundled-fixture `dns_targets`, `tcp_targets`, `quic_targets`, `domain_targets`, and a `network_snapshot` with `transport == "wifi"` and `validated == true` (so `EnvironmentRunner` does not abort).
    2. Runs the connectivity stage pipeline with a cancel token flipped after the first QUIC target.
    3. Serialises the resulting `ScanReport` to JSON and compares against a checked-in fixture (e.g., `tests/fixtures/connectivity_report_partial.json`).
  - Fixture is committed alongside the test.
  - Test deliberately uses no live network — all probes go through the bundled-fixture transport (see `direct_transport()` in `engine/runtime_tests.rs` for the existing pattern, swapping for a fixture transport if required).
  - Output JSON is stable across runs (no timestamps, ordering, or RNG seeds in the asserted shape — strip or freeze them in the test).

  Required verification:
  - `cargo nextest run -p ripdpi-monitor-engine --test contract_fixtures` green.
  - Mutating the connectivity-stage ordering or partial-results flush logic must make the test fail.

  Required reviewers:
  - Senior Rust Native Engineer.
  - QA Lead (golden review).

  Risks:
  - Time-stamp / ordering instability if not stripped → schema-only check or fixed-clock test transport required.

  Definition of done:
  - Test + fixture committed, green locally and in CI.
  - POY-12 gate G6 marked satisfied.

  Parent: POY-12.

- [ ] #task QA-A: PCAP-exclusion assertions in DiagnosticsArchiveExporterTest for every archive reason #repo/RIPDPI #area/testing #status/doing ⏫ [paperclip:POY-29]
  - Paperclip: POY-29 · assigned to: Senior Android Engineer
  
  Owner: Test Automation Engineer.
  Parent: POY-16 (Diagnostics privacy QA verification gate).
  Anchored to: POY-14 AppSec changes_requested verdict and POY-13 CTO PCAP boundary.

  Objective:
  Prove that no `*.pcap` entry leaks into normal diagnostics archives. Add per-reason assertions to `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsArchiveExporterTest.kt` and to renderer-level coverage if the cleanest gate sits at the renderer.

  Observable behavior:
  For each of `DiagnosticsArchiveReason.SHARE_ARCHIVE`, `SAVE_ARCHIVE`, `SHARE_DEBUG_BUNDLE`, `SHARE_HOME_ANALYSIS`:
  - Seed `FakeDiagnosticsHistoryStores` so that `DiagnosticsArchiveFileStore.getRecentPcapFiles()` would normally return at least one fixture file.
  - With `rootModeEnabled=false` (the non-root baseline), assert the produced zip contains zero entries whose name ends in `.pcap`.
  - Assert no PCAP byte content is written into any CSV/manifest/provenance/developer-analytics entry.
  - Assert `manifest.includedFiles` does not list a PCAP entry when the source flag is off.
  - One additional positive case: with the explicit advanced opt-in flow simulated (rootModeEnabled=true AND user explicit confirmation), the zip MAY include a PCAP entry; this asserts the gate is intentional, not accidental absence.

  Success metric / test names:
  - `createArchive excludes pcap from share archive when root mode disabled`
  - `createArchive excludes pcap from save archive when root mode disabled`
  - `createArchive excludes pcap from support bundle when root mode disabled`
  - `createArchive excludes pcap from home composite when root mode disabled`
  - `createArchive includes pcap only with explicit advanced opt-in`

  Privacy implication:
  Yes. This is the verification artifact for POY-14 PCAP exclusion. Without this test the implementation issue (Remove PCAP from normal diagnostics archives and harden developer-analytics.json) cannot close.

  Rollback note:
  If the implementation cannot honor exclusion at the source-loader layer, document the alternative gate point and wire the assertion at that layer instead — do not mark POY-16-A green until the assertion exists.

  Non-goals:
  - Do not implement the production code change in this issue. Stays scoped to test additions.

  Definition of done:
  - Five new test methods committed and passing under `./gradlew :core:diagnostics:testDebugUnitTest --tests DiagnosticsArchiveExporterTest`.
  - Linked from the implementation PR (POY-14 follow-up issue).

- [ ] #task QA-B: developer-analytics.json allow-list assertions in DiagnosticsArchiveExporterTest #repo/RIPDPI #area/testing #status/doing ⏫ [paperclip:POY-30]
  - Paperclip: POY-30 · assigned to: Senior Android Engineer
  
  Owner: Test Automation Engineer.
  Parent: POY-16 (Diagnostics privacy QA verification gate).
  Anchored to: POY-14 AppSec changes_requested verdict.

  Objective:
  Replace `NoopDeveloperAnalyticsSource` in `DiagnosticsArchiveExporterTest` with a capturing fake that produces realistic content, then assert the on-archive `developer-analytics.json` payload matches the disclosure surface on `DataTransparencyScreen` for every archive reason.

  Observable behavior:
  - Disallowed in normal exports: `lastPanicBacktrace`, `nativeLibDigests`, `breadcrumbs`, `pcapManifest`, raw config diff fields including `rootModeEnabled` and `enableCmdSettings`.
  - Allowed in normal exports: only the fields enumerated by `DataTransparencyScreen` strings.
  - The allow-list table is identical for `SHARE_ARCHIVE`, `SAVE_ARCHIVE`, `SHARE_DEBUG_BUNDLE`, `SHARE_HOME_ANALYSIS` unless POY-14 verdict explicitly carved out a different scope for the support bundle (currently it did not).
  - Negative test: any future addition to `DefaultDeveloperAnalyticsSource` fails the test until either the disclosure copy is updated or the field is excluded.

  Success metric / test names:
  - `developer analytics excludes undisclosed fields from share archive`
  - `developer analytics excludes undisclosed fields from save archive`
  - `developer analytics excludes undisclosed fields from support bundle`
  - `developer analytics excludes undisclosed fields from home composite`
  - `developer analytics allowed fields match data transparency disclosure`

  Privacy implication:
  Yes. This is the regression guard for the POY-14 hardening work.

  Rollback note:
  If the production code emits new fields, the test must fail loudly. No silent baseline expansion.

  Non-goals:
  - Do not change `DefaultDeveloperAnalyticsSource` itself in this issue.

  Definition of done:
  - Five test methods committed and passing.
  - Failing-test demo recorded in PR description showing what happens when an undisclosed field is reintroduced.

- [ ] #task QA-C: HomeScreenTest cases asserting PCAP toggle gating on rootModeEnabled #repo/RIPDPI #area/testing #status/doing ⏫ [paperclip:POY-31]
  - Paperclip: POY-31 · assigned to: Senior Android Engineer
  
  Owner: Test Automation Engineer (with Senior Android Engineer review for tag/parameter feasibility).
  Parent: POY-16 (Diagnostics privacy QA verification gate).
  Anchored to: POY-13 CTO PCAP boundary.

  Objective:
  Prove that the Home full-analysis PCAP toggle is hidden or disabled when `root_mode_enabled=false`, and that turning the setting on surfaces the toggle in the explicit opt-in state.

  Observable behavior:
  - `app/src/test/kotlin/com/poyka/ripdpi/ui/screens/home/HomeScreenTest.kt` exposes two new tests:
    - `pcap toggle hidden when root mode disabled`
    - `pcap toggle visible and disabled until opt-in when root mode enabled`
  - Tests use `RipDpiTestTags` for stable selectors. If the existing screen does not expose a tag for the PCAP toggle, this issue spawns a follow-up to add one (test code MUST NOT reach into private internals).
  - Roborazzi: confirm `RipDpiScreenCatalogScreenshotTest.homeExpandedScreen` baseline correctly reflects rootMode=false default; if a separate baseline is needed for rootMode=true, add `homeExpandedRootedScreen` capture and bless under QA review.

  Success metric / test names:
  - HomeScreenTest method names listed above.
  - Optional new Roborazzi capture in `RipDpiScreenCatalogScreenshotTest`.

  Privacy implication:
  Yes. Direct verification of the POY-13 boundary that PCAP is opt-in only.

  Rollback note:
  If rootMode is unavailable on the device class, the toggle must remain hidden (not just disabled). Test must assert `assertDoesNotExist`, not just `assertIsNotEnabled`, in that path.

  Non-goals:
  - Do not implement the gating logic itself; that belongs to the POY-13 follow-up implementation issue.

  Definition of done:
  - New tests committed and passing under `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.ui.screens.home.HomeScreenTest`.
  - If a new Roborazzi baseline is added, QA Lead reconciles it.

- [ ] #task QA-D: DataTransparencyScreenTest plus diagnostics export error-state assertions #repo/RIPDPI #area/testing #status/doing ⏫ [paperclip:POY-32]
  - Paperclip: POY-32 · assigned to: Senior Android Engineer
  
  Owner: Test Automation Engineer.
  Parent: POY-16 (Diagnostics privacy QA verification gate).
  Anchored to: POY-6 (acceptance) and the in-progress `Align diagnostics privacy and export copy` issue.

  Objective:
  1. Add a Robolectric test that asserts `DataTransparencyScreen` renders every required disclosure string from `app/src/main/res/values/strings.xml`.
  2. Add error-state assertions in `DiagnosticsScreenTest` covering archive export failure and log save failure paths.

  Observable behavior:
  New file `app/src/test/kotlin/com/poyka/ripdpi/ui/screens/settings/DataTransparencyScreenTest.kt` asserts presence of every required `R.string.data_transparency_*` id surfaced by the screen, including:
  - `data_transparency_what_we_collect_section`
  - `data_transparency_what_we_do_not_collect_section` and bullets `no_browsing`, `no_personal_data`, `no_external_servers`, `no_analytics`
  - `data_transparency_how_stored_section` and bullets `local_database`, `retention_period`, `disable_monitoring`, `export_explicit`
  - `data_transparency_export_privacy_section` and bullets `export_redaction`, `export_control`

  New assertions in `DiagnosticsScreenTest`:
  - `archive export failure shows error without leaking session payload`
  - `log save failure does not surface logcat content in error toast`

  Success metric / test names:
  - All test names above passing.
  - Linked from PR description for `Align diagnostics privacy and export copy`.

  Privacy implication:
  Yes. Directly proves the disclosure surface and the failure-path non-leak guarantees.

  Rollback note:
  If any required string id is removed during copy alignment, the test must fail; the alignment PR then has to either restore the disclosure or update the test in lockstep with QA review.

  Non-goals:
  - Do not change copy text in this issue.

  Definition of done:
  - New `DataTransparencyScreenTest` and new error-state cases in `DiagnosticsScreenTest` committed and passing.
  - Cross-referenced from the close-out comment of the `Align diagnostics privacy and export copy` issue.

- [ ] #task Surface typed cache-degradation reasons #repo/RIPDPI #area/control-plane-hardening #status/doing ⏫ [paperclip:POY-249]
  - Paperclip: POY-249 · assigned to: Senior Android Engineer
  - Parent: POY-41 (Epic - Control-plane hardening)
  - Blocks: POY-175
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **area:** android
  - **tags:** task, feature, ripdpi, control-plane, diagnostics
  - **source:** `TaskNotes/Tasks/Surface typed cache-degradation reasons.md`
  - **epic:** Epic - Control-plane hardening

  ## Summary

  Cache parse failures currently degrade silently to empty/default state via
  `runCatching{...}.getOrDefault(...)` / `getOrNull()`. Operators can't tell
  "empty by design" from "cache damaged."

  ## Audit citation

  - `app/.../hosts/HostPackCatalogRepository.kt:139-154`
  - `app/.../strategy/StrategyPackRepository.kt:145-165`

  ## Acceptance criteria

  - [ ] Add a metadata envelope around each cached snapshot: `schema_version`,
        `stored_at`, `source` (bundled / fetched).
  - [ ] Parse failures produce a typed `CacheDegradation` value
        (`Missing`, `SchemaMismatch`, `SignatureInvalid`, `Corrupt`, …) instead
        of null.
  - [ ] Degradation reason is emitted as telemetry and visible in diagnostics.
  - [ ] Callers that intentionally allow fallback opt in explicitly; they no
        longer mask corruption by accident.

  ## Links

  - [[Epic - Control-plane hardening]]
  - [[ripdpi-android-audit-2026-04-20]]


## review

- [ ] #task Define diagnostics privacy QA verification gate #repo/RIPDPI #area/testing #status/review 🔼 [paperclip:POY-16]
  - Paperclip: POY-16 · assigned to: QA Lead
  - Blocked by: POY-13, POY-14
  
  Objective:
  Define the QA verification gate for diagnostics/privacy/export acceptance criteria after the PCAP and AppSec decisions are available.

  Context:
  POY-6 defines the product acceptance checklist for diagnostics and runtime telemetry wording. Follow-up decisions POY-13 and POY-14 will determine the approved packet-capture/export boundary. QA needs a machine-verifiable gate before user-visible diagnostics, telemetry, export, settings, or privacy-copy changes proceed.

  Owner:
  QA Lead.

  User story:
  As a QA reviewer, I want diagnostics privacy requirements translated into observable tests and artifacts, so that implementation cannot ship with misleading export behavior or incomplete privacy disclosure.

  Affected surface:
  Diagnostics screen, History screen, Home analysis share controls, Advanced Settings diagnostics history controls, Data Transparency screen, settings support bundle flow, diagnostics archive contents.

  Acceptance criteria:
  1. User story: As a QA reviewer, I want diagnostics privacy requirements translated into observable tests and artifacts, so that implementation cannot ship with misleading export behavior or incomplete privacy disclosure.
  2. Observable behavior: QA posts a testability confirmation naming the required UI screenshots/tests, archive fixture checks, and manual review artifacts for each privacy-sensitive diagnostics/export surface.
  3. Success metric or test name: QA names concrete tests from `DiagnosticsArchiveExporterTest`, `DiagnosticsArchiveRendererTest`, `DiagnosticsScreenTest`, `RipDpiScreenCatalogScreenshotTest`, `AdvancedSettingsScreenCharacterizationTest`, `HomeScreenTest`, or creates follow-up automation tasks for missing coverage.
  4. Privacy implication: Yes. This is the verification gate for data collected, retained, exported, displayed, and shared by the user.
  5. Rollback note: QA must state what fallback/disabled states need verification, including diagnostics monitor off, export history off, non-root PCAP unavailable, and failed archive/log export states.
  6. Explicit non-goals: This issue does not implement tests directly unless QA chooses to create child automation work. This issue does not approve privacy copy. This issue does not run release certification.

  Privacy implication:
  High. QA verification must prove disclosure and export behavior match the AppSec-approved boundary.

  Required verification:
  QA comment with test names/artifacts and any child automation issues required.

  Required reviewers:
  Product Manager, Security/AppSec Engineer, Senior Android Engineer for UI coverage feasibility.

  Rollback note:
  If coverage is insufficient, create child test automation tasks and keep affected implementation/copy tasks blocked until coverage is present or explicitly waived by QA and PM.

  Non-goals:
  - No app or native implementation changes.
  - No release signoff.
  - No legal policy decision.

  Definition of done:
  QA posts a concrete verification matrix and creates any missing automation follow-ups needed before implementation/copy tasks can close.
