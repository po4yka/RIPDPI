# Epics — RIPDPI

> Strategic epics. Child issues live in backlog / active / blocked.

- [ ] #task Epic - Advanced routing rules and geoip enforcement #repo/RIPDPI #area/epic #status/backlog ⏫ [paperclip:POY-36]
  - Paperclip: POY-36 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, routing
  - **source:** `TaskNotes/Tasks/Epic - Advanced routing rules and geoip enforcement.md`

  ## Goal

  Finish the routing-rule story end to end. Today RIPDPI has the Protobuf
  schema for geosite and partial enforcement, plus per-app VPN exclusion, but
  no user-editable rule engine, no CIDR rules, no runtime geoip.db/geosite.db
  enforcement, and no configurable asset provider. NekoBox exposes all of
  these.

  ## Why now

  Without a rule engine, users cannot express "bypass Russian domestic banking
  while routing everything else through the proxy." This is the single most-
  requested routing primitive for bypass clients operating under whitelist-
  style censorship where split-tunneling is the norm.

  ## Key decisions

  - **Rule engine lives in Rust (runtime fast path), not Kotlin.** The Kotlin
    layer owns CRUD and serialization; the matcher is native.
  - **Rule types match NekoBox for subscription/config parity:** domain,
    domain_suffix, domain_regex, geosite, ip_cidr, geoip, port, source,
    network (tcp/udp), process, package (per-app).
  - **Outbound actions:** proxy / bypass / block / specific-profile.
  - **Asset provider is configurable** with four built-in options mirroring
    NekoBox: SagerNet, soffchen, Chocolate4U Iran rules, L11R antizapret.
    Updating is user-triggered, not silent background refresh.
  - **Integrate with the existing strategy learner:** per-domain learned
    app-family routing is a derived rule layer stacked above user rules, not
    a replacement.
  - **Custom domain bypass list has a first-class UI surface** — simpler than
    the full rule editor, for users who only want to say "keep these domains
    on direct".

  ## Scope

  - **In scope:** RuleEntity + Room table, Rust rule-matcher, runtime geoip/
    geosite.db loader, asset provider picker, custom bypass/block list UI,
    rule editor screen, rule reordering, rule enable/disable.
  - **Out of scope:** Clash-style rule-import parsers (Clash rules differ in
    semantics and aren't the point; stick with sing-box-compatible routing),
    DNS-level per-rule overrides (separate concern), automatic rule
    generation from strategy learner output (future).

  ## Ship definition

  - [ ] User can create, edit, reorder, disable, and delete routing rules
        from a dedicated Routes screen.
  - [ ] Rules support all matcher types listed in "Key decisions".
  - [ ] `geoip.db` / `geosite.db` are loaded at service start and consulted by
        the Rust matcher; lookups are O(1) after first hit.
  - [ ] Asset provider picker surfaces four built-in providers; manual file
        import via SAF also works.
  - [ ] Custom bypass list accepts newline-separated domains; entries can be
        moved into the full rule engine if needed.
  - [ ] Per-app routing (package rules) interoperates with the existing
        `VpnAppExclusionPolicy` without double-matching.
  - [ ] Rules are portable via the backup/restore flow (once shipped).
  - [ ] Rule evaluation ordering is user-controllable (drag-reorder); first
        match wins.

  ## Child tasks

  **Data and schema**
  - [[Add RuleEntity Room table and repository]]

  **Runtime**
  - [[Add Rust rule matcher with domain ip port process matchers]]
  - [[Add geoip.db and geosite.db runtime loader and lookup]]

  **Asset pipeline**
  - [[Add configurable asset provider picker with four presets]]

  **UI**
  - [[Add custom domain bypass list screen]]
  - [[Add full routing rule editor screen]]

  ## Dependencies

  - Feeds: [[Epic - Settings backup and restore]] — rules are part of backup
    schema.
  - Depends on: [[Epic - NekoBox subscription and profile import]] — rule
    outbound actions can target specific profiles or groups.

  ## Risks / open questions

  - Rule count at scale: some power-users carry 500+ rules. Keep matcher
    allocation-free in the hot loop.
  - Geosite.dat vs geosite.db formats: SagerNet and upstream have subtly
    different binary formats. Support only the SagerNet-compatible binary
    format; document.
  - Rule-engine performance with native geoip CIDR tries must beat a naive
    linear scan by a clear margin; benchmark before shipping.
  - Asset staleness: providers push updates at varying cadence; surface
    "asset is N days old" passively without nagging.

  ## Links

  - [[ripdpi-android]]
  - [[Epic - NekoBox subscription and profile import]]
  - [[Epic - Settings backup and restore]]
  - Child issues: 8

- [ ] #task Epic - AmneziaWG outbound support #repo/RIPDPI #area/epic #status/backlog 🔼 [paperclip:POY-37]
  - Paperclip: POY-37 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, protocol, amneziawg, wireguard
  - **source:** `TaskNotes/Tasks/Epic - AmneziaWG outbound support.md`

  ## Goal

  Add AmneziaWG (a DPI-resistant WireGuard fork) as a first-class outbound
  in RIPDPI so users with AmneziaWG-protected endpoints do not need a
  second app. AmneziaWG is widely deployed in Russian bypass infrastructure
  as an alternative to vanilla WireGuard, which TSPU now fingerprints
  trivially.

  ## Why now

  RIPDPI already has a mature WireGuard stack via `ripdpi-warp-core`
  (boringtun + smoltcp) for WARP. AmneziaWG adds a small set of handshake
  modifications on top of the WireGuard wire protocol; adding it is cheap
  relative to the user population gained. The feature-parity audit against
  NekoBox did not surface this (NekoBox doesn't support AWG either) — it
  is adjacent-scope expansion, not strict parity.

  ## Key decisions

  - **Fork boringtun into `ripdpi-amneziawg-core`**, do not wrap the
    Go `amneziawg-go`. RIPDPI is Rust-first; adding Go would regress the
    architecture. The AWG handshake deltas are small enough to port.
  - **Obfuscation params are server-coordinated.** Client `Jc/Jmin/Jmax/
    S1..S4/H1..H4/I1..I5` must match the server's; no auto-tuning, no
    strategy-learner variation of these params. Surface them as fixed
    config fields only.
  - **Config format:** extend the WireGuard `.conf` INI parser to
    recognize the AWG keys as optional `[Interface]` fields. An `.conf`
    without any AWG key parses as vanilla WireGuard; with any AWG key
    it routes to the AWG outbound crate.
  - **Backward compatibility:** client binary can roam between vanilla
    and AWG servers without user intervention — profile type is inferred
    from the config, not a toggle.
  - **URI codec:** use `amneziawg://` scheme for single-profile sharing
    rather than overloading `wireguard://` with AWG query params, to
    keep round-trip semantics clean.
  - **Out of scope:** kernel-module path (rooted devices); server-side
    AWG role; migration/upgrade tools between WG and AWG configs.

  ## Scope

  - **In scope:** `ripdpi-amneziawg-core` Rust crate forked from
    boringtun with AWG handshake modifications (junk packets, header
    substitution, size padding, AWG 2.0 I1–I5 intervals); Kotlin config
    model + parser extension; profile editor; URI codec; subscription-
    import routing; strategy-pack compatibility hint.
  - **Out of scope:** rooted path via amneziawg kernel module;
    AmneziaWG server mode; auto-tuning obfuscation params; an
    "AmneziaWG vs WireGuard" migration assistant.

  ## Ship definition

  - [ ] `ripdpi-amneziawg-core` crate with reference test vectors from
        amneziawg-go; all four packet types (initiation, response,
        cookie-reply, transport) support H1–H4 header substitution and
        S1–S4 size padding.
  - [ ] Jc/Jmin/Jmax junk packet generation in the handshake prelude is
        observable on the wire (packet capture shows N random packets of
        size in [Jmin, Jmax] before the real initiation).
  - [ ] AWG 2.0 I1–I5 special junk intervals land with the core work;
        not deferred.
  - [ ] Kotlin `.conf` parser accepts both vanilla WG configs (no AWG
        keys) and AWG configs (any AWG key present); round-trip through
        import → edit → save preserves all fields.
  - [ ] Profile editor exposes every AWG obfuscation field; all are
        free-text validated and surfaced inline (not hidden behind
        "Advanced").
  - [ ] `amneziawg://` URI codec exports and imports profiles with full
        field set.
  - [ ] Subscription import path: an INI-format subscription containing
        an AWG-flavored `[Interface]` block produces an AWG profile,
        not a vanilla WG profile.
  - [ ] Strategy-pack metadata flags AWG profiles as "server-coordinated
        fixed config" so the strategy learner does not vary their
        obfuscation params.
  - [ ] Secrets (private key, preshared key) redacted in all diagnostic
        surfaces and exports.

  ## Child tasks

  **Rust core**
  - [[Fork boringtun and add AmneziaWG handshake obfuscation]]

  **Kotlin config + UI**
  - [[Add AmneziaWG Kotlin config model and dot-conf parser extensions]]
  - [[Add AmneziaWG profile editor screen with obfuscation fields]]
  - [[Add amneziawg URI codec for profile share and import]]

  **Integrations**
  - [[Wire AmneziaWG into the subscription WireGuard-INI parser]]
  - [[Add strategy-pack compatibility hints for AmneziaWG servers]]

  ## Dependencies

  - Depends on: [[Add WireGuard INI subscription parser]] — the
    subscription integration task extends the same parser.
  - Depends on: [[Add ProxyGroup and Subscription entities to RIPDPI data
    layer]] — AWG profiles live in the same ProxyEntity store.
  - Feeds: [[Epic - Composable transport layer parity]] — no direct
    coupling; AWG is UDP-only and composes nothing.

  ## Risks / open questions

  - boringtun fork drift: upstream boringtun keeps moving (Cloudflare
    maintains it for WARP). Decide upfront whether we track upstream or
    hard-fork. Likely: maintain as a separate crate, cherry-pick
    upstream CVE fixes.
  - AWG 2.0 specification stability: `I1`–`I5` semantics in amneziawg-go
    v0.2.16 are still evolving. Pin a known-good amneziawg-go version
    as the reference implementation for test-vector generation.
  - Handshake-timing detection: non-zero Jc delays initiation by the
    time spent sending junk packets. Verify this does not trip RIPDPI's
    own direct-mode verdict state machine (which expects timely
    initiations).
  - uTLS / fingerprint interactions: AWG is UDP-only; there is no TLS
    ClientHello to spoof. But if the server sits behind a TLS-over-UDP
    obfuscation layer (some deployments do this), the AWG stack must
    not assume it owns the raw UDP socket.
  - License: boringtun is BSD-3; amneziawg-go is MIT. Any code ported
    from amneziawg-go must carry MIT attribution; do not mix
    boringtun BSD-3 source with MIT-derived AWG patches without clear
    file-level licensing headers.

  ## Links

  - [[ripdpi-android]]
  - [[Add WireGuard INI subscription parser]]
  - Reference implementation: https://github.com/amnezia-vpn/amneziawg-go
  - Reference Android client: https://github.com/amnezia-vpn/amneziawg-android (local: `/Users/po4yka/GitRep/amneziawg-android/`)
  - Child issues: 6

- [ ] #task Epic - Boot autostart and session persistence #repo/RIPDPI #area/auth #status/backlog 🔼 [paperclip:POY-38]
  - Paperclip: POY-38 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, automation
  - **source:** `TaskNotes/Tasks/Epic - Boot autostart and session persistence.md`

  ## Goal

  Resume the user's active RIPDPI session after device reboot without the user
  needing to open the app. Today the app has no boot receiver; every reboot
  forces a manual reconnect.

  ## Why now

  Censorship-bypass clients are expected to be always-on. A user who reboots
  overnight should wake up tunneled. This is a small, well-scoped epic that
  materially changes daily-driver ergonomics.

  ## Key decisions

  - **Boot receiver is opt-in and disabled by default.** Enable only when
    "Start on boot" is toggled on. Dynamic component-state so the receiver
    does not keep the package alive when unused.
  - **Direct-boot (`LOCKED_BOOT_COMPLETED`) is supported,** so the tunnel
    comes up before the user unlocks. Settings and active-profile selection
    must be accessible from device-protected storage.
  - **Persist the chosen service mode and active profile ID,** not the live
    session state. On boot, reconstruct the session; do not try to restore
    in-flight connections.
  - **Guard on battery-saver and doze exclusion:** do not auto-start if the
    user denied background permission.
  - **Never start on `MY_PACKAGE_REPLACED` alone** without user consent; an
    update is not a reboot.

  ## Scope

  - **In scope:** `BootReceiver` (BOOT_COMPLETED + LOCKED_BOOT_COMPLETED
    + MY_PACKAGE_REPLACED), start-on-boot user toggle, last-active-profile
    persistence in direct-boot-aware storage, Settings permission guard.
  - **Out of scope:** scheduled on/off timers (separate automation feature),
    network-change triggered restart (already handled by
    `NetworkHandoverMonitor`), carrier/roaming conditional autostart.

  ## Ship definition

  - [ ] User toggles "Start on boot" in Settings; `BootReceiver` is enabled
        only while this toggle is on.
  - [ ] After reboot, if toggle is on and a last-active profile exists, the
        previously selected service (VPN or Proxy mode) resumes.
  - [ ] Direct-boot path works: tunnel up before lockscreen unlock.
  - [ ] Battery-saver / doze whitelist guard prompt appears once when the
        toggle is first enabled; rejection disables the toggle.
  - [ ] Package replacement (app update) does not auto-restart unless the
        session was actively running at update time.
  - [ ] No sensitive data (UUIDs, keys, server addresses) lands in direct-
        boot storage that is device-protected only, not user-protected.

  ## Child tasks

  - [[Add boot-completed receiver with dynamic enable]]
  - [[Add last-active-profile persistence in direct-boot storage]]
  - [[Add start-on-boot user toggle and permission guard]]
  - [[Add package-replaced restart gated on prior running state]]

  ## Dependencies

  - Depends on: [[Epic - System HTTP proxy service mode]] — receiver must
    resume whichever service mode was active, not default.

  ## Risks / open questions

  - Chinese OEM ROM background policies (Xiaomi, Huawei, Oppo, Vivo, Samsung
    "Sleeping apps") silently kill auto-start. Document the vendor-specific
    whitelist steps in onboarding rather than fighting each ROM.
  - Direct-boot storage split: ensure the secret-bearing profile fields never
    land in device-protected storage, only a non-sensitive pointer.
  - `MY_PACKAGE_REPLACED` gate: distinguish "was running before update" from
    "was set up on boot"; only the first justifies auto-restart post-update.

  ## Links

  - [[ripdpi-android]]
  - Child issues: 4

- [ ] #task Epic - Cloudflare publish hardening #repo/RIPDPI #area/cloudflare #status/backlog ⏫ [paperclip:POY-39]
  - Paperclip: POY-39 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, cloudflare
  - **source:** `TaskNotes/Tasks/Epic - Cloudflare publish hardening.md`

  ## Goal

  Remove session-to-session state leakage, reentrancy risk, and unnecessary
  flash churn from the Cloudflare publish runtime. Binaries installed once
  per ABI/version; working state is ephemeral; credentials are cleaned on
  stop; concurrent starts refused cleanly.

  ## Why now

  The audit found four stacked problems in one subsystem:
  `CloudflarePublishManager.start()` doesn't reject already-running sessions;
  `DefaultCloudflarePublishRuntimeFactory` returns a singleton; binaries
  copy from assets on every start; named-tunnel credentials live in
  `filesDir` beyond session lifetime. `allowBackup="false"` prevents backup
  leak, but the on-device persistence is still unnecessary and slow.

  ## Key decisions

  - **Per-session runtime instance.** No shared mutable state between
    sessions. Each session owns its own runtime object, which is thrown
    away at stop.
  - **Binary install once, keyed by `(ABI, version hash)`.** Hash-verified
    on every subsequent start; asset version change invalidates.
  - **Ephemeral working dir** (`cacheDir` or a session-scoped subdir) for
    anything that isn't legitimately persistent operator configuration.
  - **Credential cleanup on stop** (both happy-path and error); orphan
    cleanup on startup for crashed-prior-run cases.

  ## Scope

  - **In scope:** `CloudflarePublishManager`, `CloudflarePublishRuntime`,
    `DefaultCloudflarePublishRuntimeFactory`, binary install path,
    credential persistence on `filesDir`.
  - **Out of scope:** non-Cloudflare publish paths (separate stack).

  ## Ship definition

  - [ ] Concurrent `start()` on a running session returns a typed
        `AlreadyRunning` error, not undefined behavior.
  - [ ] `DefaultCloudflarePublishRuntimeFactory` no longer hands out a
        singleton — each session receives its own.
  - [ ] Binary install measured to happen at most once per ABI+version hash
        per install; cold-start latency drops measurably.
  - [ ] No credential files remain in `filesDir` after a clean stop;
        crashed-prior-run files are cleaned at startup.

  ## Child tasks

  **Reentrancy**
  - [[Reject concurrent CloudflarePublishManager sessions]]

  **State isolation**
  - [[Per-session CloudflarePublishRuntime instances]]
  - [[Clean up Cloudflare credential artifacts on stop]]

  **Install path**
  - [[Install Cloudflare binaries once per ABI and version]]

  Child tasks roll up via the TaskNotes relationships view on this note.

  ## Risks / open questions

  - Install-cache invalidation semantics on asset version bump — decide
    during the install task (delete everything, or keep N-1 for rollback?).
  - Ensure ephemeral-dir cleanup doesn't race with next session's startup.

  ## Links

  - [[ripdpi-android]]
  - [[ripdpi-android-audit-2026-04-20]] §7
  - Child issues: 4

- [ ] #task Epic - Composable transport layer parity #repo/RIPDPI #area/epic #status/backlog ⏫ [paperclip:POY-40]
  - Paperclip: POY-40 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, transports
  - **source:** `TaskNotes/Tasks/Epic - Composable transport layer parity.md`

  ## Goal

  Close the transport-layer gaps uncovered by the 2026-04-24 audit so every
  outbound protocol in RIPDPI can use every carrier NekoBox ships: plain
  TCP, TLS, Reality, WebSocket, gRPC, HTTP/2, HTTP/3, QUIC, HTTPUpgrade,
  xHTTP, and wire-level multiplexing. Today transports are protocol-locked
  (H3/QUIC only in Hysteria2/MASQUE; WebSocket only in the Telegram MTProto
  crate) and three transports are missing outright (gRPC, HTTPUpgrade,
  sing-mux/yamux/smux wire protocol).

  ## Why now

  Outbound protocol crates (Epic - Extended outbound protocol support) must
  compose with a transport to be useful. Trojan-over-WebSocket, VLESS-over-
  gRPC, VMess-over-H2, and Trojan-over-HTTPUpgrade are common real-world
  subscription shapes. Without a composable transport layer, every new
  protocol epic produces half-usable outbounds. Ship the transports once,
  wire every protocol through them.

  ## Key decisions

  - **Transports live in their own crates** (`ripdpi-transport-ws`,
    `ripdpi-transport-grpc`, `ripdpi-transport-httpupgrade`,
    `ripdpi-transport-mux`, `ripdpi-transport-quic`). Each exposes an
    `AsyncRead + AsyncWrite` (or `Sink + Stream` for datagram) surface
    that any outbound crate can layer onto.
  - **Pick WebSocket from the existing `ripdpi-ws-tunnel` crate and
    generalize,** do not duplicate. The Telegram-only call site becomes
    one consumer of the generic crate.
  - **gRPC uses `tonic`** with protobuf framing; do NOT roll our own gRPC.
  - **Mux: ship sing-mux + yamux.** `smux` is the odd-one-out (Trojan-Go
    only); ship it if and only if real Trojan-Go subscriptions need it.
  - **QUIC/H3 composable transport reuses the existing `quinn` + `h3`
    stack** from Hysteria2/MASQUE; refactor to a shared crate rather than
    two protocol-locked copies.
  - **uTLS + Reality + ECH are already ahead of NekoBox.** Do NOT
    regress; transport crates must accept a uTLS-capable TLS connector
    as the composition point.

  ## Scope

  - **In scope:** five new or generalized transport crates; adapter
    traits; wire-format conformance tests against upstream sing-box
    fixtures; composition docs (which protocol × transport combinations
    are expected to work).
  - **Out of scope:** meek / meek-lite (deprecated); obfs4 over non-
    standard transports (Lyrebird already covers obfs4); custom TLS-
    fragmentation layers beyond finalmask (already present).

  ## Ship definition

  - [ ] `ripdpi-transport-ws` is a generic WebSocket transport composable
        under at least three outbounds (Trojan, VLESS, VMess).
  - [ ] `ripdpi-transport-grpc` implements Xray-compatible gRPC framing
        (service name `proxy.v2ray.com.Service`, method `Tun`) via `tonic`.
  - [ ] `ripdpi-transport-httpupgrade` speaks the HTTP/1.1 Upgrade dance
        used by Xray/V2Fly `httpupgrade` inbound.
  - [ ] `ripdpi-transport-mux` implements sing-mux and yamux wire
        protocols with upstream-parity test vectors.
  - [ ] `ripdpi-transport-quic` exposes a composable QUIC stream and
        datagram transport usable under VLESS (VLESS-QUIC), VMess, and
        future protocols.
  - [ ] Every current outbound crate continues to pass its existing
        tests; no regression.
  - [ ] Documentation table in `docs/transports.md` lists every
        protocol × transport combination and whether it is supported,
        not-supported-by-design, or pending implementation.

  ## Child tasks

  - [[Generalize WebSocket transport for outbound composition]]
  - [[Add gRPC transport crate with tonic and Xray-compatible framing]]
  - [[Add HTTPUpgrade transport crate]]
  - [[Add sing-mux and yamux wire multiplexing]]
  - [[Refactor QUIC and H3 into a composable transport crate]]

  ## Dependencies

  - Feeds: [[Epic - Extended outbound protocol support]] — every new
    outbound crate in that epic can pick from the composable transport
    set; several subscription shapes (Trojan-WS, VLESS-gRPC,
    VMess-H2-HTTPUpgrade) cannot work without these transports.
  - Feeds: [[Epic - NekoBox subscription and profile import]] — Clash
    and sing-box subscription parsers can populate
    transport-specific fields once the transports exist.

  ## Risks / open questions

  - gRPC over a uTLS-spoofed TLS is non-trivial; `tonic` wants its own
    `rustls` connector. Decide: expose a `hyper` client with a
    swappable connector, or ship a thin `tonic` alternative that
    accepts a raw TCP+TLS stream.
  - Wire-mux on Android may raise memory pressure under many parallel
    flows; benchmark the pool-size choice before shipping defaults.
  - Composable QUIC transport means VLESS-QUIC becomes possible — but
    subscription providers rarely ship VLESS-QUIC profiles. Ship the
    composability; surface the profile type as "advanced" in UI.
  - uTLS fingerprint parity under gRPC may degrade JA3/JA4 scores;
    validate against the existing golden fixtures in `ripdpi-tls-
    profiles`.

  ## Links

  - [[ripdpi-android]]
  - [[Epic - Extended outbound protocol support]]
  - Child issues: 6

- [ ] #task Epic - Control-plane hardening #repo/RIPDPI #area/epic #status/todo 🔺 [paperclip:POY-41]
  - Paperclip: POY-41 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, control-plane
  - **source:** `TaskNotes/Tasks/Epic - Control-plane hardening.md`

  ## Goal

  Replace the current fragile catalog download path (same-origin checksums,
  no anti-rollback, non-atomic writes, setting-triggered refreshes) with a
  signed, rollback-resistant, atomic, TTL-gated control plane. Outcome: an
  old valid-signed payload can't downgrade the client, a mid-write crash
  can't corrupt the cache, and unrelated settings edits don't hit the network.

  ## Why now

  The 2026-04-20 audit rated strategy/host catalog trust as the single
  weakest link in the project's security story. Fixing this first prevents
  building new features on top of a control plane that may ship fragile.

  ## Key decisions

  - **Signed manifests for host packs** using the same app-trusted key infra
    as strategy packs (decide reuse vs new key before implementation).
  - **Monotonic sequence + freshness** inside the signed envelope for both
    pack types; reject stale on principle, allow rollback only via an
    explicit local override.
  - **AtomicFile (or temp-file + fsync + rename)** for every cache write; a
    torn file must never appear at the canonical path.
  - **Refresh is scheduled, not eager.** Trigger on the narrow tuple
    `(channel, refreshPolicy, pinnedPackId, pinnedPackVersion)` with
    `distinctUntilChanged` + TTL + backoff.

  ## Scope

  - **In scope:** strategy-pack refresh discipline, host-pack signature
    model, anti-rollback, atomic snapshot writes, typed degradation
    telemetry.
  - **Out of scope:** transport/runtime changes, operator UX beyond the
    degradation-reason surfacing.

  ## Ship definition

  - [ ] Unsigned or invalid-signature host-pack payload is rejected with a
        typed error.
  - [ ] Older-sequence strategy-pack payload is rejected without the debug
        local override.
  - [ ] Process kill mid-write of either cache leaves the prior snapshot
        intact and readable.
  - [ ] Unrelated app-setting edits produce zero strategy-pack network I/O
        (measured in a unit test).
  - [ ] Cache parse failures surface as typed `CacheDegradation` reasons,
        not silent empty state.

  ## Child tasks

  **Refresh discipline**
  - [[Tighten strategy-pack refresh discipline]]

  **Signing and anti-rollback**
  - [[Sign host-pack manifests with app-trusted keys]]
  - [[Add anti-rollback to strategy-pack updates]]
  - [[Spike signed route-pack schema for direct-vs-relay policy]]

  **Crash-safe storage**
  - [[Make cache snapshot writes atomic]]
  - [[Surface typed cache-degradation reasons]]

  Child tasks roll up via the TaskNotes relationships view on this note.

  ## Dependencies

  - Unblocks: [[Add control-plane rollback attempt test]] and
    [[Add cache-corruption regression test]] under
    [[Epic - Orchestration test posture]].
  - Unblocks: [[Build CensorLab-style offline strategy-pack pipeline]] under
    [[Epic - Privacy-preserving strategy learner]] (generated packs must fit
    the hardened signed format).

  ## Risks / open questions

  - Signing model for host packs: reuse the strategy-pack key or issue a
    new one? Decide before the signing task lands.
  - Rollback override UX: settings toggle, CLI flag, or debug-only? Keep it
    boring and hard to find by accident.
  - `autoArchiveDelay` coupling to status changes — ensure degraded-source
    telemetry doesn't accidentally auto-archive the related notes.

  ## Links

  - [[ripdpi-android]]
  - [[ripdpi-android-audit-2026-04-20]] §1, §2, §3, Highest-ROI #1
  - Child issues: 3

- [ ] #task Epic - Direct-mode diagnostic state machine #repo/RIPDPI #area/epic #status/todo ⏫ [paperclip:POY-42]
  - Paperclip: POY-42 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, direct-mode, diagnostic
  - **source:** `TaskNotes/Tasks/Epic - Direct-mode diagnostic state machine.md`

  ## Goal

  Orchestrate the subsystems (DNS classifier, transport policy, TLS family
  engine, strategy learner, owned-stack) into a single diagnostic that
  returns one of three honest verdicts: `TRANSPARENT_WORKS`,
  `OWNED_STACK_ONLY`, `NO_DIRECT_SOLUTION`. The integration epic that makes
  the rest of the direct-mode stream user-visible.

  ## Why now

  The subsystem epics are useful individually, but the user-facing
  capability is "tell me whether I can reach this host directly, and if
  so, how." Without this epic, five good subsystems produce no product.

  ## Key decisions

  - **Sealed `DiagnosticResult` taxonomy** with structured reason codes:

  ```text
  DiagnosticResult = TRANSPARENT_WORKS
                   | OWNED_STACK_ONLY
                   | NO_DIRECT_SOLUTION { reason: IP_BLOCKED | ... }
  ```

  - **Phase 0 passive observation before active probing.** Extract what we
    can from the last failed flow (DNS outcome, fail phase, blockpage
    shape) so we don't probe from zero every time.
  - **Six-phase flow** matching the plan: 0 passive obs → 1 DNS class → 2
    transport class → 3 ranked arms → 4 execute with early stop + confirm
    → 5 persist with revalidation → 6 rotate within winner's neighborhood.
  - **Per-class arm list is fixed** (see
    [[ripdpi-android-direct-mode-plan-2026-04-20]] Phase 3), then
    reranked by the learner's local priors.
  - **TTL-gated persistence.** 7-day default; invalidate on ASN change,
    access-type change, 3 consecutive failures, HTTPS/SVCB TTL expiry, or
    ECH capability change.
  - **Hard separation of product modes.** Transparent-mode arms (A3–A8)
    and owned-stack arms (A9–A10) execute through different code paths
    with different invariants.

  ## Scope

  - **In scope:** direct-mode product-mode boundary, `DiagnosticResult`
    types and classification taxonomy, Phase 0 passive observation, Phases
    1–4 orchestration, Phase 5 persistence and revalidation, Phase 6
    variant rotation, integration tests per result class.
  - **Out of scope:** subsystem internals (owned by the other epics).

  ## Current landing

  The repo-owned direct-mode state machine is now substantially more real,
  but the epic is still not fully closed:

  - typed direct-mode verdicts are now persisted and surfaced end to end
    through the diagnostics engine wire contract and summary layer;
  - the positive `TRANSPARENT_WORKS` outcome is now visible instead of being
    dropped on the floor by the display-summary path;
  - persisted `strategyRecommendation` is available again to the Home audit
    workflow, so the subsystem outputs now survive finalization and storage;
  - diagnostics finalization now consults the last stored authority policy
    before pinning a new verdict, which gives the current implementation a
    lightweight Phase 0 passive prior from the last confirmed flow;
  - persisted direct-mode policy now honors `confirm-before-pin`: transparent
    / owned-stack outcomes need corroborating evidence or a matching prior,
    while negative outcomes need repeated active failures before they become
    stored policy;
  - Phase 5 persistence is now partially landed in repo scope: stored
    authority policy has a 7-day TTL, runtime ignores unconfirmed entries,
    and three consecutive revalidation failures retire the cached policy.

  Still open: the explicit ranked-arm dispatcher, hard attempt-budget
  enforcement, the exact class-to-arm ladder from the plan, ASN /
  HTTPS-RR-specific invalidation triggers, and deterministic integration
  coverage for every result class.

  ## Ship definition

  - [x] One diagnostic run produces exactly one `DiagnosticResult`
        variant, each with a structured reason.
  - [ ] Attempt budget hard-enforced (delegated to
        [[Enforce diagnostic attempt budget]]).
  - [ ] Per-class arm lists match the plan exactly:
    - `DNS_BLOCK`: A1, A3, A4, A5, A6, A10, A9
    - `SNI_TLS_SUSPECT`: A3, A5, A6, A7, A8, A10, A9
    - `QUIC_BLOCK_SUSPECT`: A3, A4, A5, A6, A9
    - `IP_BLOCK_SUSPECT`: A10, A9
    - `UNKNOWN`: A1, A3, A4, A5, A9
  - [x] Phase 4 success requires a confirmation request before pinning
        in the repo-owned persistence path.
  - [ ] Persisted verdict invalidates on every revalidation trigger.
  - [ ] Integration tests cover every result class on a deterministic
        harness (no sleep-based waits).

  ## Child tasks

  **Boundary and types**
  - [[Define transparent vs owned-stack mode boundary]]
  - [[Define DiagnosticResult and classification taxonomy]]

  **Phases**
  - [[Implement Phase 0 passive observation from last flow]]
  - [[Implement direct-mode diagnostic orchestrator Phases 1-4]]
  - [[Persist direct-mode policy with revalidation]]

  **Integration tests**
  - [[Add integration tests per diagnostic result class]]

  **Remediation and handoff**
  - [[Replace generic relay suggestion with transport-specific remediation ladder]]

  Child tasks roll up via the TaskNotes relationships view on this note.

  ## Current landing status

  As of 2026-04-23, the transport-specific remediation child task is partially
  landed in `/Users/po4yka/GitRep/RIPDPI`: Diagnostics and Home now branch from
  typed direct-mode verdicts into owned-stack, browser-camouflage relay,
  QUIC-heavy relay, or "no reliable relay hint" ladders instead of one generic
  relay fallback. The remaining gap in this epic area is config-side unification:
  relay preset suggestions still use their older heuristic path rather than the
  same shared remediation selector.

  ## Dependencies

  Aggregates subsystem outputs from every direct-mode subsystem epic:

  - [[Epic - Encrypted DNS and HTTPS SVCB classifier]] — Phase 1
  - [[Epic - Direct-mode transport policy and verdicts]] — Phase 2
  - [[Epic - Semantic TLS first-flight family engine]] — arms A5–A8
  - [[Epic - Privacy-preserving strategy learner]] — Phase 3 ranking
  - [[Epic - Owned-stack mode with Android 17 ECH]] — arms A9, A10
  - [[Epic - Orchestration test posture]] — failure-injection harness for
    integration tests

  ## Risks / open questions

  - Phase 4 `confirm_once` semantics: does a 2nd request to the same host
    really confirm, or could a CDN return-to-sender make it look
    successful? Define "stable success" precisely in the orchestrator task.
  - "Known blockpage" response-shape heuristic: starts conservative,
    tuned from real captures.

  ## Links

  - [[ripdpi-android]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]] "Basic diagnostic: full
    state machine"
  - Child issues: 7

- [ ] #task Epic - Direct-mode transport policy and verdicts #repo/RIPDPI #area/epic #status/todo ⏫ [paperclip:POY-43]
  - Paperclip: POY-43 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, direct-mode, transport, quic
  - **source:** `TaskNotes/Tasks/Epic - Direct-mode transport policy and verdicts.md`

  ## Goal

  Decide per-`(host, ip set, app family, network profile)` when to disable
  QUIC, detect apps that can't fall back to TCP without breaking, classify
  true IP blocks, and return honest `NO_DIRECT_SOLUTION` verdicts instead
  of burning attempts. The policy engine's job is to stop thrashing when
  direct transparent mode can't win.

  ## Why now

  Aggressive QUIC packet rewriting in transparent TUN mode is expensive
  and brittle. The plan deliberately moves that complexity out of the
  default path. But without a policy engine, the diagnostic has no way to
  say "this is likely IP-blocked, stop trying transport tricks." That
  wasted effort is a battery, network, and detectability tax.

  ## Key decisions

  - **QUIC suppression, not QUIC rewriting.** Drop outbound UDP/443 per
    tuple; let the app retry over TCP where the TLS family engine can do
    its job.
  - **NO_TCP_FALLBACK** is a per-app-family memory. If soft-disable
    breaks an app that hard-depends on QUIC, remember and don't apply
    again.
  - **Three outcome types:** `TRANSPARENT_OK`, `OWNED_STACK_ONLY`,
    `NO_DIRECT_SOLUTION`. Each with a structured reason code visible in
    diagnostics.
  - **Relay-assisted QUICstep migration is out of scope** for this epic —
    it belongs in a second-tier rescue mode, not the "no remote proxy"
    default.

  ## Scope

  - **In scope:** `TransportPolicy` struct, QUIC `SOFT_DISABLE` /
    `HARD_DISABLE` enforcement, `NO_TCP_FALLBACK` detection,
    `IP_BLOCK_SUSPECT` classification, `NO_DIRECT_SOLUTION` surfacing,
    per-tuple policy cache.
  - **Out of scope:** QUIC packet-level rewriting, relay-assisted
    transport migration, non-443 QUIC ports (yagni).

  ## Ship definition

  - [x] `TransportPolicy` type exists with all five fields
        (`quic_mode`, `preferred_stack`, `dns_mode`, `tcp_family`,
        `outcome`) and serializes stably across app updates.
  - [ ] `SOFT_DISABLE` is tuple-scoped — other hosts and other apps
        unaffected.
  - [ ] `NO_TCP_FALLBACK` heuristic is conservative by default; reverts on
        app package version change.
  - [x] `IP_BLOCK_SUSPECT` classification re-verifies on the next flow
        before pinning, to avoid transient-blip false positives.
  - [x] `NO_DIRECT_SOLUTION` surface in UI with a structured reason, and
        with cooldown to prevent immediate re-runs.

  ## Implementation note

  As of 2026-04-23, the honest-verdict slice is live in
  `/Users/po4yka/GitRep/RIPDPI`: diagnostics now keep distinct TLS, QUIC,
  and likely-IP-block `NO_DIRECT_SOLUTION` causes instead of collapsing them
  all into `IP_BLOCK_SUSPECT`, runtime `ALL_IPS_FAILED` learning now requires
  a second flow before persisting the negative verdict, and the runtime
  enforcement path now applies the cached tuple-scoped QUIC suppression more
  consistently. In particular, `NO_TCP_FALLBACK` no longer leaves the runtime in
  the contradictory state where UDP suppression is lifted but the adaptive
  UDP/QUIC hint layer still behaves as if QUIC is broken for the same
  authority. Remaining work is now the true per-app-family `NO_TCP_FALLBACK`
  memory and invalidation on app package-version change.

  ## Child tasks

  **Struct and cache**
  - [[Define TransportPolicy struct and per-host state]]
  - [[Cache transport policy per network and host tuple]]

  **QUIC control**
  - [[Implement QUIC soft-disable per tuple]]
  - [[Detect NO_TCP_FALLBACK app families]]

  **Verdict classification**
  - [[Classify IP_BLOCK_SUSPECT when all IPs fail]]
  - [[Surface NO_DIRECT_SOLUTION verdict honestly]]

  Child tasks roll up via the TaskNotes relationships view on this note.

  ## Dependencies

  - Feeds: [[Epic - Direct-mode diagnostic state machine]] Phase 2 + arm A3.
  - Consumed by: [[Gate DoQ on UDP-clean classification]] under
    [[Epic - Encrypted DNS and HTTPS SVCB classifier]] (DoQ gate reads
    `udp443_ok`).
  - Unblocks: [[Report OWNED_STACK_ONLY verdict from diagnostic]] under
    [[Epic - Owned-stack mode with Android 17 ECH]].

  ## Risks / open questions

  - `NO_TCP_FALLBACK` heuristic: how to detect reliably without breaking
    the app the first time? Spike the detection signal before committing.
  - Cooldown length for `NO_DIRECT_SOLUTION`: too short wastes retries,
    too long looks broken on recovery. Default 7 days (matches Phase 5
    TTL), revalidate on ASN/access-type change.
  - If a later second-tier rescue track evaluates relay-assisted QUICstep,
    keep it strictly post-`NO_DIRECT_SOLUTION` and outside the default
    transparent-mode path. See
    [[Spike relay-assisted QUICstep rescue mode after NO_DIRECT_SOLUTION]].

  ## Links

  - [[ripdpi-android]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]] §3, Basic diagnostic
    Phase 2 + arm A3
  - Child issues: 8

- [ ] #task Epic - Encrypted DNS and HTTPS SVCB classifier #repo/RIPDPI #area/dns #status/todo 🔺 [paperclip:POY-44]
  - Paperclip: POY-44 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, direct-mode, dns
  - **source:** `TaskNotes/Tasks/Epic - Encrypted DNS and HTTPS SVCB classifier.md`

  ## Goal

  Make DNS a first-class bypass layer and a first-class classifier. Separate
  DNS poisoning from SNI/TLS blocking from QUIC filtering from IP blocking
  before the diagnostic burns any transport-level attempts.

  ## Why now

  TSPU blocks by DNS manipulation too, not only by SNI or IP. Without
  classifying DNS first, the diagnostic will cycle through transport tricks
  against a host it could have reached by simply switching resolvers. Also,
  HTTPS/SVCB records carry ECH config metadata that gates
  [[Epic - Owned-stack mode with Android 17 ECH]].

  ## Key decisions

  - **DoH by default.** It rides HTTPS and survives hostile UDP.
  - **DoQ gated.** Only activated after the transport policy engine marks
    UDP/443 healthy for the current network profile. Otherwise DoQ and
    QUIC fail together.
  - **Always query HTTPS/SVCB** alongside A/AAAA/CNAME — these carry ALPN
    hints and ECH configs and are cheap enough to piggyback.
  - **Five-state classification** produced for every target:

  | State             | Meaning |
  |-------------------|---------|
  | `CLEAN`           | System and encrypted resolvers agree materially |
  | `POISONED`        | System returns NXDOMAIN / empty / known bad; encrypted returns valid |
  | `DIVERGENT`       | Both valid but different CDN answers; no strong poisoning evidence |
  | `ECH_CAPABLE`     | HTTPS RR carries ECH config metadata |
  | `NO_HTTPS_RR`     | No HTTPS/SVCB data available |

  - **No broad preloaded scanning.** Measure only destinations the user is
    actually trying to reach (C-Saw consent posture).

  ## Scope

  - **In scope:** DoH primary+secondary pipeline, DoQ gated on UDP-clean,
    HTTPS/SVCB RR queries with ECH config parsing, DNS classification,
    resolver selection logic, user-destinations-only measurement.
  - **Out of scope:** running a DoH/DoQ resolver ourselves.

  ## Ship definition

  - [ ] Resolver cascade runs per-target: system → DoH primary → DoH
        secondary → DoQ (if UDP clean).
  - [ ] A/AAAA/CNAME/HTTPS/SVCB queried in one batch; HTTPS RR ECH config
        parsed into a typed `EchConfig`.
  - [x] Classification produces exactly one of the five states above on the
        active native `dns_integrity` path, and that classifier is persisted
        into direct-path capability envelopes.
  - [ ] No code path exists that probes a preloaded target list.
  - [ ] Selection cache keyed by `(host, NetProfile)` with the same TTL as
        the family cache.

  ## Child tasks

  **Resolver pipeline**
  - [[Build DoH primary and secondary resolver pipeline]]
  - [[Gate DoQ on UDP-clean classification]]

  **HTTPS/SVCB**
  - [[Parse HTTPS SVCB records with ECH config metadata]]

  **Classification**
  - [[Classify DNS as clean poisoned divergent ech-capable]]
  - [[Select resolver mapping from DNS classification]]

  **Privacy posture**
  - [[Limit DNS measurement to user-requested destinations]]

  Child tasks roll up via the TaskNotes relationships view on this note.

  ## Dependencies

  - Feeds: [[Epic - Direct-mode diagnostic state machine]] Phase 1 and
    arms A0–A2.
  - Coordinates with: [[Epic - Direct-mode transport policy and verdicts]]
    (DoQ gating depends on `udp443_ok` from transport policy).

  ## Risks / open questions

  - DoH resolver selection: which providers, which redundancy? Decide in
    the pipeline task.
  - Caching policy: HTTPS/SVCB TTL vs field-observed staleness — surface
    staleness via the Phase 5 revalidation triggers.
  - "Known bad IP" heuristic for POISONED classification: start
    conservative to avoid false positives; tune from field data.

  ## Implementation note

  As of 2026-04-23, RIPDPI now ships the classifier itself on the live native
  DNS-probe path, threads the result into direct-path policy storage, applies
  authority-scoped encrypted-DNS resolver selection on the native hostname-
  resolution path, and downgrades DoQ back to DoH whenever the current host is
  not UDP-clean under transport policy. VPN startup also now promotes converged
  hostname-backed `DOH_PRIMARY` / `DOH_SECONDARY` guidance into the active
  resolver selection instead of waiting for reactive failover. What remains open
  in this epic is the follow-on cache/policy work: a dedicated fastest-resolver
  cache keyed by `(host, NetProfile)` and any richer `DIVERGENT` correlation
  logic beyond the current policy-hint path.

  ## Links

  - [[ripdpi-android]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]] §2, Basic diagnostic
    Phase 1 + arms A0–A2
  - Child issues: 3

- [ ] #task Epic - Extended outbound protocol support #repo/RIPDPI #area/epic #status/backlog ⏫ [paperclip:POY-45]
  - Paperclip: POY-45 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, protocols
  - **source:** `TaskNotes/Tasks/Epic - Extended outbound protocol support.md`

  ## Goal

  Cover every outbound protocol type that realistic third-party subscriptions
  still ship: Shadowsocks, HTTP(S), SOCKS5, VMess, Trojan, Trojan-Go, SSH,
  AnyTLS, Mieru, and Hysteria v1. Today RIPDPI fronts the modern stack
  (VLESS-Reality, Hysteria2, TUIC, WARP, ShadowTLS, Naive, MASQUE, xHTTP) but
  cannot consume nodes published in these older or commodity formats, forcing
  users to maintain a second app. The biggest gap is **Shadowsocks**, which
  is the most common protocol across real-world bypass subscriptions; RIPDPI
  currently only has SS as an inbound framing format, not a full outbound
  client.

  ## Why now

  Subscription import (blocking epic) is only useful if the protocols listed in
  the subscription can be executed. VMess and Trojan are the most common in
  Russian/Iranian/Chinese bypass scenes after VLESS-Reality; skipping them
  cripples subscription adoption.

  ## Key decisions

  - **Native Rust crates, mirroring existing pattern** (`ripdpi-vless`,
    `ripdpi-hysteria2`, `ripdpi-tuic`, `ripdpi-shadowtls`). No external C/Go
    binaries in the outbound path for these.
  - **Protocol inclusion bar: must be present in realistic bypass
    subscriptions.** The full matrix is Shadowsocks, HTTP(S), SOCKS5,
    VMess, Trojan, Trojan-Go, SSH, AnyTLS, Mieru, Hysteria-v1.
    **Tor is deliberately excluded** — RIPDPI already ships obfs4 and
    Snowflake via the Lyrebird binary, which covers the Tor-bridge use
    case without pulling in Tor's directory/consensus layer. SOCKS4/4a
    are deliberately excluded as legacy with negligible presence.
  - **SSH is included** because it remains a common relay for hobbyist
    censorship-bypass setups, despite low share-count; the existing `ripdpi-
    warp-core` noise primitives are unrelated — SSH needs its own crypto.
  - **VMess is included but marked legacy.** New subscriptions should not rely
    on it; we support decoding/consuming but do not surface it in the
    new-profile UI beyond an "advanced / legacy" expander.
  - **Hysteria v1 is included for transition,** but once subscriptions have
    fully migrated to v2 the v1 crate should be removed, not left to rot.

  ## Scope

  - **In scope:** Rust crates for Shadowsocks, HTTP(S), SOCKS5, VMess,
    Trojan, Trojan-Go, SSH, AnyTLS, Mieru, Hysteria v1 outbounds; UI
    editor screens; URI codec extension; integration into the existing
    relay supervisor model; strategy-pack compatibility hints per
    protocol.
  - **Out of scope:** Tor (see exclusion rationale above), Brook, SOCKS4/4a,
    other SagerNet-branded protocols; inbound server roles for any of these;
    Shadowsocks plugins (simple-obfs, v2ray-plugin) — a follow-up epic if
    real subscription samples demand them.

  ## Ship definition

  - [ ] `ripdpi-shadowsocks`, `ripdpi-http-proxy`, `ripdpi-socks5-client`,
        `ripdpi-vmess`, `ripdpi-trojan`, `ripdpi-trojan-go`, `ripdpi-ssh`,
        `ripdpi-anytls`, `ripdpi-mieru`, and `ripdpi-hysteria-v1` crates
        exist, unit-tested against upstream reference test vectors.
  - [ ] Each protocol has a profile-edit screen with schema-backed validation.
  - [ ] Each protocol can be parsed from its standard URI scheme into a valid
        RIPDPI profile and round-tripped back to URI.
  - [ ] Strategy-pack metadata includes per-protocol compatibility hints
        (e.g. Trojan inside xHTTP, SSH direct vs SSH-over-TLS).
  - [ ] Relay supervisor can start and stop each protocol cleanly; shutdown
        joins bounded handler work (same invariant as existing protocols).
  - [ ] Secrets (passwords, UUIDs, private keys) are redacted in logs,
        diagnostics, and crash reports, not only at export time.

  ## Child tasks

  **Foundational (common in subscriptions)**
  - [[Add Shadowsocks outbound client crate and profile editor]]
  - [[Add HTTP and SOCKS5 outbound proxy clients]]

  **Protocol long tail**
  - [[Add VMess outbound client crate and profile editor]]
  - [[Add Trojan outbound client crate and profile editor]]
  - [[Add Trojan-Go outbound client crate and profile editor]]
  - [[Add SSH outbound client crate and profile editor]]
  - [[Add AnyTLS outbound client crate and profile editor]]
  - [[Add Mieru outbound client crate and profile editor]]
  - [[Add Hysteria v1 outbound client crate and profile editor]]

  ## Dependencies

  - Unblocks: subscription-driven deployment in [[Epic - NekoBox subscription
    and profile import]]; without these crates, VMess/Trojan/Hysteria-v1 nodes
    in imported subscriptions cannot actually connect.

  ## Risks / open questions

  - VMess AEAD vs legacy security variants: pick a supported matrix and reject
    unsupported modes with typed errors, not silent downgrade.
  - SSH channel multiplexing adds complexity; consider single-channel v1 before
    committing to full multiplexing.
  - Strategy-pack cross-product explodes with five new protocols; keep
    per-protocol recommended arms tight.
  - Hysteria v1 removal timeline needs a committed sunset date to avoid
    long-tail maintenance.

  ## Links

  - [[ripdpi-android]]
  - [[Epic - NekoBox subscription and profile import]]
  - Child issues: 9

- [ ] #task Epic - Fail-closed Android VPN policy engine #repo/RIPDPI #area/android #status/backlog 🔺 [paperclip:POY-46]
  - Paperclip: POY-46 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, vpn, policy, security
  - **source:** `TaskNotes/Tasks/Epic - Fail-closed Android VPN policy engine.md`

  ## Goal

  Make RIPDPI a fail-closed policy-first Android VPN client, not just a GUI for imported proxy links. The app should eliminate the common failure classes in existing clients: incomplete policy bundles, DNS and IPv6 leaks, weak kill-switch UX, shared subscriptions, manual-only failover, unsafe logs, and untested VPN lifecycle behavior.

  ## Scope

  - In scope: Android VpnService lifecycle, lockdown onboarding, DNS and IPv6 policy, priority failover, typed policy profile schema, per-device subscription handling, secret storage, no-secret diagnostics, and regression tests.
  - Out of scope: server-side subscription delivery implementation, payment flows, non-Android clients, and replacing existing direct-mode or Xray-provider epics.

  ## Status

  New cross-cutting hardening epic derived from the client-problem analysis. It coordinates with Xray VPN mode, subscription import, advanced routing, QR/import, and runtime lifecycle epics.

  ## Child work

  - [[Define policy bundle profile schema]]
  - [[Define split-strict DNS policy model]]
  - [[Add Android lockdown onboarding and kill-switch health checks]]
  - [[Enforce fail-closed VpnService lifecycle]]
  - [[Add DNS interceptor and split DNS leak tests]]
  - [[Implement scoped bootstrap DNS allowlist]]
  - [[Implement strict tunneled DNS resolver failover]]
  - [[Bind DNS answers to route decisions]]
  - [[Add explicit IPv6 policy modes and leak tests]]
  - [[Add priority-based outbound failover state machine]]
  - [[Add per-device subscription token UX and shared-link warnings]]
  - [[Encrypt VPN profiles with Android Keystore]]
  - [[Add no-secret logging and diagnostics redaction tests]]
  - [[Add NetworkCallback reconnect and underlying-network tracking]]
  - [[Add captive-portal and whitelist-mode connection states]]
  - [[Add captive portal DNS assist via Network object]]
  - [[Add Android Private DNS conflict warning]]
  - [[Harden DoH POST resolver client]]
  - [[Add authoritative DNS leak-test harness]]
  - [[Add Android VPN leak-test instrumentation matrix]]

  ## Milestones

  - [ ] Internal VPN profile is a typed policy bundle, not only imported URI strings.
  - [ ] Secure default captures full-device traffic with DNS interception and explicit IPv4-only policy.
  - [ ] Lockdown onboarding clearly distinguishes Android system kill switch from soft reconnect.
  - [ ] Core crash, network switch, and VPN revoke paths fail closed in tests.
  - [ ] Logs, diagnostics, crash exports, QR/import, and subscription refreshes redact live credentials.

  ## Risks

  - Android lockdown state is partly user/system controlled; the app must not overclaim hard kill-switch guarantees.
  - DNS and IPv6 policy cuts across direct-mode, Xray provider mode, and subscription rendering.
  - Per-app policy changes require VPN session re-establish and can conflict with user expectations under lockdown.

  ## Notes

  This epic intentionally removes an entire class of client problems rather than mirroring individual behavior from v2rayNG, NekoBox, Streisand, or sing-box GUI clients.

  ## Links

  - [[ripdpi-android]]
  - [[ripdpi-android-split-strict-dns-architecture-2026-05-01]]
  - [[Epic - Xray VPN client mode]]
  - [[Epic - NekoBox subscription and profile import]]
  - [[Epic - Advanced routing rules and geoip enforcement]]
  - [[Epic - Runtime lifecycle and supervisors]]
  - https://developer.android.com/develop/connectivity/vpn
  - Child issues: 21

- [ ] #task Epic - Localization expansion #repo/RIPDPI #area/epic #status/backlog 🔼 [paperclip:POY-47]
  - Paperclip: POY-47 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, i18n
  - **source:** `TaskNotes/Tasks/Epic - Localization expansion.md`

  ## Goal

  Reach realistic language coverage for the target user base. Today RIPDPI
  ships English and Russian only; NekoBox ships ~20 locales. Pick the subset
  that corresponds to the actual bypass-user geographies and get translations
  landed with a pipeline that sustains updates.

  ## Why now

  Most of the actual bypass-client user base outside Russia is in Persian-,
  Chinese-, and Arabic-speaking regions. Shipping EN+RU only is a hard
  adoption barrier in those geographies. Picking a translation pipeline now
  also prevents the one-off-PR-per-language chaos other projects suffer.

  ## Key decisions

  - **Pick a self-hosted pipeline (Weblate) over a SaaS (Crowdin)** to avoid
    coupling release cadence to an external service that could be geofenced
    or priced-out. Defer final decision to the pipeline task but bias toward
    self-hosted.
  - **First wave targets user-geography match:** zh-CN, fa (Persian),
    ar (Arabic), de, es, fr. These cover ~70% of realistic non-RU users
    sampled from community chat demographics.
  - **Do not machine-translate and ship.** All strings go through a human
    translator before merging. MT pre-translations are acceptable as a
    starting point for translators, not a shipping state.
  - **String freeze N weeks before release.** Translators need a stable
    source.
  - **Drop strings that aren't translator-safe** (e.g., protocol names,
    acronyms, technical keys) via the standard Android `translatable="false"`
    marker.

  ## Scope

  - **In scope:** translation pipeline selection and setup, `values-zh-rCN`,
    `values-fa`, `values-ar`, `values-de`, `values-es`, `values-fr` initial
    wave; `translatable="false"` audit on existing strings; right-to-left
    layout verification for Arabic and Persian.
  - **Out of scope:** the full NekoBox locale set (20+). Add additional
    languages as Tier 2 when pipeline is live and community interest
    materializes. No in-app language picker — rely on system locale.

  ## Ship definition

  - [ ] Translation pipeline is documented in `docs/`; a new contributor can
        open a PR with a new locale by following README steps only.
  - [ ] `values-zh-rCN`, `values-fa`, `values-ar`, `values-de`, `values-es`,
        and `values-fr` directories exist and cover ≥95% of `values/` strings.
  - [ ] RTL layout renders correctly in fa and ar (screenshot tests under
        Roborazzi cover the main screens in each).
  - [ ] `translatable="false"` is set on strings that must not be translated
        (protocol names, internal keys).
  - [ ] A CI check fails the build if a new source string is added without
        being picked up by the pipeline export.

  ## Child tasks

  - [[Select and set up translation pipeline for RIPDPI]]
  - [[Add zh-CN translation and initial human review]]
  - [[Add fa ar de es fr translations and RTL screenshot tests]]

  ## Dependencies

  - None hard-blocking. Best landed after the subscription/profile/routing
    epics stabilize so translators are not chasing moving strings.

  ## Risks / open questions

  - Weblate self-hosting cost and ops; if the maintainer cannot absorb it,
    fall back to a read-only fork-based PR workflow (no runtime service).
  - Translator recruiting; community chats are the most realistic source but
    introduce moderation overhead.
  - RTL regression risk; bake in Roborazzi RTL variants during the setup task
    rather than adding post hoc.
  - Locale-specific font fallbacks for Persian/Arabic with the Geist family —
    verify glyph coverage or wire fallbacks.

  ## Links

  - [[ripdpi-android]]
  - Child issues: 3

- [ ] #task Epic - Native hotspot decomposition #repo/RIPDPI #area/epic #status/backlog 🔼 [paperclip:POY-48]
  - Paperclip: POY-48 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, rust, refactor
  - **source:** `TaskNotes/Tasks/Epic - Native hotspot decomposition.md`

  ## Goal

  Split the three oversized hot files by responsibility so future protocol
  and evasion work doesn't serialize through the same three modules. Extract
  a first-class internal `ActionPlan` IR in the Rust runtime as a side
  benefit that makes planner, emitter, and fallback logic independently
  testable.

  ## Why now

  Audit quantifies the concentration: `desync.rs` 1538 LOC mixing planning,
  fallback choice, fake-packet construction, TTL-sensitive send logic, and
  plan execution; `linux.rs` 1557 LOC mixing socket options, protect logic,
  raw sends, TCP repair, TTL capture, and low-level packet mutation;
  `RipDpiProxyJsonCodec.kt` 708 LOC mixing schema, migration, validation,
  and rewrite. Every future change piles on unless we refactor now.

  ## Key decisions

  - **Split by responsibility, not by arbitrary LOC.** `desync.rs` →
    `planner / emitters / fallback_classifier / fake_packet`. `linux.rs` →
    `sockopts / protect / raw_send / tcp_repair`. Codec → `schema /
    migration / validation / rewrite`.
  - **ActionPlan IR first.** The planner module becomes the natural home
    for a typed plan; emitter/platform code consumes it. Keep the IR
    internal to the Rust runtime initially — no JNI exposure.
  - **Preserve behavior.** Existing integration and fuzz coverage must stay
    green throughout. Each split is a pure refactor.

  ## Scope

  - **In scope:** `desync.rs`, `linux.rs`, `RipDpiProxyJsonCodec.kt`,
    introduction of a first-class `ActionPlan` IR.
  - **Out of scope:** oversized Kotlin UI screens (separate cleanup track);
    any behavior change that isn't required by the split.

  ## Ship definition

  - [ ] `desync.rs` and `linux.rs` each sit comfortably below a sustainable
        LOC budget per resulting file (target: <800 LOC per file post-split).
  - [ ] `RipDpiProxyJsonCodec.kt` modules are each <300 LOC.
  - [ ] `config/static/file-loc-baseline.json` updated; no new oversized
        files added by the split.
  - [ ] `ActionPlan` IR exists, has unit tests, and at least one call site
        is migrated as a pilot.
  - [ ] No existing test regresses.

  ## Child tasks

  **Rust split**
  - [[Decompose desync.rs by responsibility]]
  - [[Decompose linux.rs by responsibility]]
  - [[Extract native ActionPlan IR]]

  **Kotlin split**
  - [[Decompose RipDpiProxyJsonCodec]]

  Child tasks roll up via the TaskNotes relationships view on this note.

  ## Risks / open questions

  - IR shape: what belongs in the `ActionPlan` vs left to emitters? Prototype
    before committing to a public module surface.
  - Fuzz coverage may bit-exactly match current structures — verify the fuzz
    harness still hits the moved code after each split.

  ## Links

  - [[ripdpi-android]]
  - [[ripdpi-android-audit-2026-04-20]] §10, Highest-ROI #3
  - Child issues: 4

- [ ] #task Epic - NekoBox subscription and profile import #repo/RIPDPI #area/subscription #status/backlog 🔺 [paperclip:POY-49]
  - Paperclip: POY-49 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, subscriptions
  - **source:** `TaskNotes/Tasks/Epic - NekoBox subscription and profile import.md`

  ## Goal

  Let users load and keep fresh their proxy nodes from standard censorship-bypass
  subscription providers, the same way NekoBox does. Today RIPDPI only ships
  built-in operator presets and ad-hoc user relays; there is no way to paste a
  subscription URL and get a populated group with periodic refresh.

  ## Why now

  Subscription management is the single largest feature RIPDPI lacks compared to
  the NekoBox feature surface. Without it, users of third-party providers cannot
  adopt the app without manual per-node entry. This is the gating item for
  real-world adoption.

  ## Key decisions

  - **Keep the Rust engine; only add parsing/transport layers.** No sing-box
    runtime swap.
  - **Support the subscription formats NekoBox parses,** not a broader set:
    Clash/Clash.Meta YAML, sing-box JSON outbound array, WireGuard INI,
    base64 URI list, plain URI list.
  - **Parse in Kotlin, not Rust,** for iteration speed and to keep the Rust
    engine focused on the runtime fast path.
  - **Refresh via WorkManager with min 15-min cadence,** matching NekoBox.
  - **Redact secrets on every log and diagnostic surface from day one.**
  - **Preserve per-profile custom overrides** (`customOutboundJson`,
    `customConfigJson`) across subscription merges so user tweaks survive.

  ## Scope

  - **In scope:** ProxyGroup/SubscriptionBean entities, five subscription
    parsers, per-protocol URI codec for import/export, auto-update worker,
    force-resolve DNS option, dedup, quota tracking from `Subscription-Userinfo`
    header.
  - **Out of scope:** Clash routing rules (parsers should ignore them), sing-box
    inbound/route sections, V2rayN legacy share links beyond common vmess/vless,
    proxy chaining (separate concern, not on roadmap).

  ## Ship definition

  - [ ] User can paste a subscription URL in a group-edit screen and see the
        populated profile list within the same session.
  - [ ] All five subscription formats parse without exceptions on a realistic
        sample bank.
  - [ ] `Subscription-Userinfo` header (upload/download/total/expiry) is
        surfaced in the group detail screen.
  - [ ] Auto-update fires via WorkManager at the group's configured cadence,
        gated by "update when connected only" when set.
  - [ ] Duplicate profiles (byte-equal minus display name) are detected and
        merged on refresh without losing user-edited names.
  - [ ] User-edited `customOutboundJson` / `customConfigJson` overrides survive
        subscription refresh.
  - [ ] Subscription URLs, tokens, and server addresses never appear in logs,
        diagnostics exports, or crash reports.

  ## Child tasks

  **Data model**
  - [[Add ProxyGroup and Subscription entities to RIPDPI data layer]]
  - [[Add duplicate-profile detection on subscription merge]]
  - [[Add selector outbound runtime for group-based profile switching]]

  **Parsers**
  - [[Add Clash and Clash.Meta YAML subscription parser]]
  - [[Add sing-box JSON subscription parser]]
  - [[Add WireGuard INI subscription parser]]
  - [[Add base64 and plain URI-list subscription parser]]

  **Refresh and transport**
  - [[Add subscription auto-update WorkManager worker]]
  - [[Add force-resolve DNS and Subscription-Userinfo handling]]

  ## Dependencies

  - Feeds: [[Epic - QR code and clipboard profile import]] (shares URI codec).
  - Feeds: [[Epic - Advanced routing rules and geoip enforcement]] (groups may
    expose selector outbound state that rule engine consumes).

  ## Risks / open questions

  - Clash-format drift: Clash.Meta YAML keeps adding fields. Design parsers to
    ignore unknown keys rather than hard-fail.
  - Subscription-Userinfo trust: some providers lie. Display as informational;
    never use for billing-style gating.
  - Large subscriptions (500+ nodes): ensure parser is streaming, not loading
    the whole YAML into memory.
  - WireGuard INI multi-peer: pick one active peer per parse; surface the others
    as separate profiles.

  ## Links

  - [[ripdpi-android]]
  - [[wikis/mobile-platform-enforcement/index|mobile-platform-enforcement]]
  - Child issues: 9

- [ ] #task Epic - Orchestration test posture #repo/RIPDPI #area/testing #status/todo ⏫ [paperclip:POY-50]
  - Paperclip: POY-50 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, testing
  - **source:** `TaskNotes/Tasks/Epic - Orchestration test posture.md`

  ## Goal

  Close the three untested-class gaps the audit surfaced, and build one
  shared deterministic failure-injection harness that every orchestration-
  level scenario test reuses. Parser/config fuzzing is already good — this
  epic is specifically about orchestration and lifecycle, where the bugs
  hide today.

  ## Why now

  Cache corruption, supervisor lifecycle edges, rollback attempts, and
  protect-socket stalls all happen rarely in production and are impossible
  to reproduce without deterministic injection. Every audit-stream fix
  benefits from having a test that would have caught the original bug, and
  shares more infrastructure than it would build alone.

  ## Key decisions

  - **One shared harness** (fake clock, scripted network, corrupt-file
    fixture, scripted exit causes, stall injection for the protect socket)
    — not bespoke fixtures per scenario.
  - **Scenario tests block the fixes they regress-protect** via
    `blockedBy`, so the harness lands first and the scenarios follow as
    each matching fix merges.
  - **Unit coverage for the three untested classes**
    (`DefaultStrategyPackService`, `AppStartupInitializer`,
    `VpnProtectSocketServer`) is a separate task from the scenario
    harness — different failure mode, different fixture needs.

  ## Scope

  - **In scope:** shared failure-injection harness; unit tests for the
    three untested classes; scenario tests for cache corruption, repeated
    startup/shutdown, control-plane rollback, protect-socket stall.
  - **Out of scope:** parser/config fuzz coverage (already good); UI
    tests; end-to-end device tests.

  ## Ship definition

  - [ ] Harness documented in the test-module README with a minimal
        example.
  - [ ] Four scenario tests use the harness and pass deterministically (no
        sleep-based waiting).
  - [ ] Each of the three previously-untested classes has a dedicated test
        file covering the failure modes the audit called out.
  - [ ] CI green on main after every fix-and-test pair merges.

  ## Child tasks

  **Harness**
  - [[Add orchestration failure-injection harness]]

  **Unit coverage for untested classes**
  - [[Add unit tests for orchestration gaps]]

  **Scenario tests** (each `blockedBy` the harness)
  - [[Add cache-corruption regression test]]
  - [[Add repeated startup-shutdown supervisor test]]
  - [[Add control-plane rollback attempt test]]
  - [[Add protect-socket server stall test]]

  Child tasks roll up via the TaskNotes relationships view on this note.

  ## Dependencies

  - Depends on: [[Epic - Control-plane hardening]] (rollback / atomic
    writes must exist before their regression tests).
  - Depends on: [[Epic - Runtime lifecycle and supervisors]] (explicit
    exit causes must exist before the supervisor lifecycle test).
  - Depends on: [[Epic - Privacy and diagnostics]] (reworked protect
    socket must exist before the stall test's assertions make sense).
  - Shares test coverage with:
    [[Epic - Direct-mode diagnostic state machine]] (integration tests per
    result class, also `blockedBy` this harness).

  ## Risks / open questions

  - Fake-clock discipline: any test that uses real time introduces
    flakiness. Linter rule to reject real-clock calls inside harness-
    governed tests?

  ## Links

  - [[ripdpi-android]]
  - [[ripdpi-android-audit-2026-04-20]] §"Test posture", Highest-ROI #4
  - Child issues: 2

- [ ] #task Epic - Privacy-preserving strategy learner #repo/RIPDPI #area/epic #status/todo ⏫ [paperclip:POY-53]
  - Paperclip: POY-53 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, direct-mode, bandit, privacy
  - **source:** `TaskNotes/Tasks/Epic - Privacy-preserving strategy learner.md`

  ## Goal

  Pick a working arm quickly with few attempts, low detectability, and low
  battery cost. Local Bayesian bandit per
  `(NetProfile, HostProfile, Arm)`; strict attempt budgets; opt-in shared
  priors that never leak user URLs, SSIDs, or precise location.

  ## Why now

  The plan's explicit bottleneck is no longer "parse harder packets" — it's
  "find a working arm in under 6 seconds with fewer than 5 attempts." The
  research literature (C-Saw on measurement-with-consent; recent detection
  work on accumulation-based host profiling) points to exactly this shape
  of learner as the right answer.

  ## Key decisions

  - **Beta posterior with four penalty terms:**

  ```text
  score = posterior
        - 0.10 * normalized_ttfb
        - 0.08 * normalized_bytes_overhead
        - 0.15 * repeated_attempt_penalty
        - 0.20 * rarity_penalty
  ```

  - **Rarity penalty from local frequency,** not a preset label — an arm
    becomes "rare" when we haven't observed similar wire images recently.
    Protects against accumulation-based detection.
  - **Strict attempt budget** per diagnostic run:

  ```text
  max_active_arms = 5
  max_elapsed_ms  = 6000
  max_probe_bytes = 65536
  stop_on_first_stable_success = true
  ```

  - **Opt-in shared priors with coarse keys only.** Upload batches keyed
    by `(asn, access_type, dns_class, udp443_ok, fail_phase)` — no URLs,
    no SSIDs, no precise location. Enforced at serialization type level,
    not by runtime filtering.
  - **CensorLab-style offline emulator** for strategy-pack generation so
    we get ahead of future censor behavior instead of reacting after
    users break.
  - **Asymmetric decay:** successful families decay more slowly than
    failed exact variants. A single failure must not wipe a well-earned
    prior.

  ## Scope

  - **In scope:** `NetProfile` / `HostProfile` / `ArmStats` types, Beta
    posterior scoring with rarity + repeated-attempt penalties, attempt-
    budget enforcement, decay policy, opt-in shared-priors uploader with
    coarse-key schema, CensorLab-style offline generator.
  - **Out of scope:** training remote ML models on user traffic; any path
    that would upload per-flow detail.

  ## Ship definition

  - [ ] Three types defined, serde-stable, with zero user-identifying
        fields.
  - [ ] Arm ranking exercises all four penalty terms; unit tests cover
        each in isolation.
  - [ ] Attempt budget hard-enforced; each cap has a unit test that shows
        it firing first.
  - [ ] Shared-priors uploader passes a static-analysis test that proves
        it cannot depend on URL- or SSID-carrying types.
  - [ ] Offline emulator produces packs that fit the signed-pack format
        from [[Add anti-rollback to strategy-pack updates]].

  ## Current status

  The first offline-generation slice is now landed in
  `/Users/po4yka/GitRep/RIPDPI`:

  - the existing offline analytics pipeline no longer stops at device-fingerprint
    clusters and winner mappings; it now also emits a review-gated
    `strategy-pack-catalog.candidate.json`
  - generated packs reuse the live strategy-pack schema and baseline catalog
    metadata, and append staged `offline-*` packs derived from stable winner
    mappings
  - the slice is still intentionally offline-only: generated packs are not
    consumed by runtime ranking automatically and still require analyst review
    plus the normal signing/promotion flow
  - the runtime learner pieces remain open: Bayesian scoring, rarity/retry
    penalties, attempt-budget enforcement, and shared-priors serialization rules

  ## Child tasks

  **Types**
  - [[Define NetProfile HostProfile and ArmStats]]

  **Ranking**
  - [[Implement Bayesian posterior arm scoring]]
  - [[Add rarity and repeated-attempt penalties to arm ranking]]
  - [[Decay successful families slower than failed variants]]

  **Budget enforcement**
  - [[Enforce diagnostic attempt budget]]

  **Shared priors and offline generation**
  - [[Opt-in shared priors with coarse keys only]]
  - [[Build CensorLab-style offline strategy-pack pipeline]]

  Child tasks roll up via the TaskNotes relationships view on this note.

  ## Dependencies

  - Feeds: [[Epic - Direct-mode diagnostic state machine]] (Phase 3 arm
    ranking consumes this learner).
  - Depends on (for offline pipeline):
    [[Add anti-rollback to strategy-pack updates]] and
    [[Sign host-pack manifests with app-trusted keys]] under
    [[Epic - Control-plane hardening]].

  ## Risks / open questions

  - Coarse-key entropy: how many buckets before `(asn, access_type,
    dns_class, udp443_ok, fail_phase)` becomes identifying? Audit on
    real-world data before enabling the upload by default.
  - Emulator sim-to-field gap: calibrate on known field failures before
    any generated pack ships.

  ## Links

  - [[ripdpi-android]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]] §5
  - Child issues: 7

- [ ] #task Epic - QR code and clipboard profile import #repo/RIPDPI #area/subscription #status/backlog ⏫ [paperclip:POY-54]
  - Paperclip: POY-54 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, onboarding
  - **source:** `TaskNotes/Tasks/Epic - QR code and clipboard profile import.md`

  ## Goal

  Make single-profile import frictionless. Users should be able to scan a QR
  code, paste a link from the clipboard, or tap a share-sheet entry and land on
  a populated profile-edit screen.

  ## Why now

  Every real-world bypass community distributes individual nodes as
  `vless://…` / `hy2://…` / `tuic://…` share links, often inside a QR image.
  Without these paths, onboarding requires typing server addresses by hand.
  This is the second-largest onboarding gap after subscription import.

  ## Key decisions

  - **Use CameraX + ML Kit barcode scanner,** not zxing, to keep dependency
    count low and match the existing androidx posture.
  - **Share the URI codec with the subscription epic,** not a duplicate parser.
  - **Clipboard watcher is opt-in,** not default, to avoid violating the
    minimum-permission stance. Triggered by a notification action when the
    app is foregrounded, not on paste-in-other-apps.
  - **QR output (for sharing) is generated offline,** no network round-trip.

  ## Scope

  - **In scope:** camera-based QR scan, image-file QR decode, paste-from-
    clipboard flow, share-sheet target for `ripdpi://` and common proxy URI
    schemes, QR generation for exporting a single profile.
  - **Out of scope:** batch QR scanning (a subscription-in-QR is unusual and
    covered by the URL import path), QR-code deep linking to Google Play (no
    distribution coupling), OCR of non-QR images.

  ## Ship definition

  - [ ] User can scan a QR containing `vless://`, `vmess://`, `trojan://`,
        `ss://`, `hysteria2://`, `tuic://`, `anytls://`, or `ripdpi://` and
        land on a populated profile-edit screen.
  - [ ] User can decode a QR from an image picked via SAF.
  - [ ] User can paste a proxy URI from the clipboard via an explicit "Import
        from clipboard" menu; clipboard is never read silently.
  - [ ] User can generate a shareable QR code and standard URI from any saved
        profile, with secrets redaction warning shown once.
  - [ ] Camera permission flow degrades gracefully to image-file path when
        denied.
  - [ ] Profile-URI export is intercepted by the system share sheet.

  ## Child tasks

  - [[Add QR scanner screen with CameraX and ML Kit]]
  - [[Add QR generation and share for saved profiles]]
  - [[Add clipboard-import menu action with explicit user consent]]
  - [[Add share-sheet handler for proxy URI schemes]]

  ## Dependencies

  - Depends on: [[Epic - NekoBox subscription and profile import]] (shared URI
    codec must exist first).

  ## Risks / open questions

  - Camera permission rejection rate is high; make sure the image-file path
    feels first-class, not a fallback.
  - ML Kit pulls a modelled barcode scanner; verify final APK size impact
    against the "no Play Services" posture and consider the on-device unbundled
    model variant.
  - Share-sheet interception can conflict with the browser; register a low-
    priority filter and only claim specific proxy schemes.

  ## Links

  - [[ripdpi-android]]
  - [[Epic - NekoBox subscription and profile import]]
  - Child issues: 4

- [ ] #task Epic - Remove Cloudflare from critical path #repo/RIPDPI #area/cloudflare #status/backlog 🔺 [paperclip:POY-55]
  - Paperclip: POY-55 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** vps
  - **tags:** task, epic, vps, ripdpi, cloudflare, resilience
  - **source:** `TaskNotes/Tasks/Epic - Remove Cloudflare from critical path.md`

  ## Goal

  Remove Cloudflare from every critical path for Russian users while keeping it as an optional low-priority fallback where it still works.

  ## Scope

  - In scope: dependency audit, non-Cloudflare delivery, non-Cloudflare DNS fallback, direct/non-CF HTTPS fallback, client selector changes, large-payload health checks, per-ISP monitoring, and migration runbook.
  - Out of scope: deleting all Cloudflare usage, Cloudflare enterprise static IP procurement, and storing live endpoints or tokens in TaskNotes.

  ## Status

  New cross-project resilience epic derived from the 2026-05-01 Cloudflare RU degradation brief.

  ## Child work

  - [[Audit Cloudflare-only dependencies]]
  - [[Provision non-Cloudflare delivery host]]
  - [[Add multi-delivery subscription mirror support]]
  - [[Add Cloudflare large-payload healthcheck]]
  - [[Demote Cloudflare profiles from default auto selection]]
  - [[Add non-Cloudflare HTTPS XHTTP fallback frontend]]
  - [[Remove Cloudflare DNS from critical resolver chain]]
  - [[Add Cloudflare degradation classification runbook]]
  - [[Add Russian ISP payload monitoring probes]]

  ## Milestones

  - [ ] No production profile requires Cloudflare for primary transport.
  - [ ] Subscription delivery works through at least one non-Cloudflare endpoint.
  - [ ] DNS bootstrap and tunneled DNS have non-Cloudflare paths.
  - [ ] Cloudflare XHTTP/HTTPS profiles are manual or low priority when degraded.
  - [ ] Monitoring detects Cloudflare-like 16 KB payload throttling, not just TLS success.

  ## Risks

  - Direct fallback hostnames change the origin exposure threat model.
  - Alternative CDNs can become the same failure class if all choices are foreign hyperscale edges.
  - Adding multiple delivery mirrors must not create shared subscription URLs or token leakage.

  ## Notes

  Keep live hostnames, tokens, and provider details out of this note. Store sensitive operational mapping under `ops/live-infra/`.

  ## Links

  - [[cloudflare-ru-critical-path-removal-2026-05-01]]
  - [[vps-proxy-fleet]]
  - [[ripdpi-android]]
  - [[Epic - Fail-closed Android VPN policy engine]]
  - [[Epic - NekoBox subscription and profile import]]
  - Child issues: 6

- [ ] #task Epic - Runtime lifecycle and supervisors #repo/RIPDPI #area/epic #status/todo 🔺 [paperclip:POY-56]
  - Paperclip: POY-56 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, runtime, lifecycle
  - **source:** `TaskNotes/Tasks/Epic - Runtime lifecycle and supervisors.md`

  ## Goal

  Make runtime lifecycle explicit and deterministic. Replace poll-and-guess
  startup with native readiness events, move exit-cause semantics into the
  supervisors themselves (sealed type, caller-independent), and decouple the
  JNI wrappers' handle-lifetime locking from their ordinary telemetry path.

  ## Why now

  Three supervisors today fire `onUnexpectedExit` on every completion, with
  correctness resting on a caller-owned `stopping` flag. Telemetry errors
  are collapsed via `runCatching { ... }.getOrNull()`. JNI wrappers poll
  every 50 ms to detect readiness, holding a coarse mutex while they do.
  Every future runtime change has to navigate this. Fixing it now is the
  last chance to do it cheaply.

  ## Key decisions

  - **Sealed `ExitCause`** replacing the flag-based approach:
    `ExpectedStop`, `Crash(code)`, `StartupFailure(throwable)`,
    `Cancellation`. Each supervisor produces exactly one per run.
  - **Two locks in JNI wrappers**: one for handle lifetime (create/destroy
    serialization), one for ordinary telemetry/config against a live
    handle. Telemetry no longer head-of-line-blocks lifecycle.
  - **Native event channel** for readiness. Design spike decides JNI
    callback vs eventfd/pipe surfaced through JNI.
  - **Typed telemetry results** — no more `getOrNull()`-into-void. Engine
    errors surface, "no data yet" stays distinct from "engine failed."

  ## Scope

  - **In scope:** `AppStartupInitializer`, three runtime supervisors
    (proxy / upstream relay / warp), JNI wrappers (`RipDpiProxy`,
    `RipDpiRelay`), native readiness events.
  - **Out of scope:** runtime feature work, protocol changes, UI reporting
    beyond what the new types make possible.

  ## Ship definition

  - [ ] Expected vs unexpected exit is observable from the supervisor's
        output alone — callers no longer maintain a `stopping` flag.
  - [ ] `pollTelemetry()` call sites produce typed results; no
        `getOrNull()` remains on those paths.
  - [ ] Startup failure in one subsystem does not mask the others; the
        startup report is structured per-subsystem.
  - [ ] Native readiness latency measured before/after; 50 ms polling loop
        removed.
  - [ ] No behavior regression in existing supervisor/lifecycle tests.

  ## Child tasks

  **Startup**
  - [[Split AppStartupInitializer failure domains]]

  **Supervisor exit semantics**
  - [[Add explicit supervisor exit cause types]]
  - [[Type-safe pollTelemetry results]]

  **JNI wrappers**
  - [[Decouple JNI handle-lifetime and telemetry locking]]
  - [[Add native readiness events to RipDpi wrappers]]

  Child tasks roll up via the TaskNotes relationships view on this note.

  ## Dependencies

  - Unblocks: [[Add repeated startup-shutdown supervisor test]] under
    [[Epic - Orchestration test posture]] (needs scripted exit causes).

  ## Risks / open questions

  - JNI callback model vs pollable fd: spike before implementation.
    Thread-ownership concerns differ materially.
  - Avoid a lock-hierarchy regression when splitting handle-lifetime from
    telemetry locks — document the acquisition order.

  ## Links

  - [[ripdpi-android]]
  - [[ripdpi-android-audit-2026-04-20]] §4, §5, §6, Highest-ROI #2
  - Child issues: 4

- [ ] #task Epic - Settings backup and restore #repo/RIPDPI #area/epic #status/backlog 🔼 [paperclip:POY-58]
  - Paperclip: POY-58 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, data-portability
  - **source:** `TaskNotes/Tasks/Epic - Settings backup and restore.md`

  ## Goal

  Let users export and restore their RIPDPI configuration (profiles, groups,
  routing rules, user settings) through a controlled, redacted JSON file.
  NekoBox has this; RIPDPI does not, and currently pins `allowBackup="false"`
  explicitly.

  ## Why now

  Two deployments want this: (a) device migration without re-entering every
  subscription URL; (b) pre-sanitized sharing of a diagnostic bundle with a
  teammate. Both need a schema that includes profiles but can redact secrets.

  ## Key decisions

  - **Two export modes: FULL and SHARE.** FULL keeps credentials (for same-
    device restore); SHARE redacts UUIDs, passwords, private keys, and server
    addresses.
  - **Schema is versioned and forward-compatible.** Unknown keys are ignored
    on import. Schema version bumps must describe migration.
  - **SAF-only file I/O;** never write backups to a hardcoded path. Export
    defaults to the Downloads bucket via `CreateDocument`.
  - **`allowBackup=false` stays.** This is a user-initiated export, not
    auto-backup. Do not re-enable Android Backup Service.
  - **Partial restore allowed:** profiles, routing, settings each selectable
    independently.
  - **Restore requires app restart** via `ProcessPhoenix`-equivalent because
    DataStore + Room listeners in-flight need clean reinit.

  ## Scope

  - **In scope:** backup JSON schema, FULL and SHARE export modes, SAF export
    and import flows, selective restore UI, share-sheet intent for SHARE
    output, secret redaction rules, reset-all-settings action.
  - **Out of scope:** encrypted backup files (use device-level file encryption
    or user-provided password in a future follow-up), cloud backup integration,
    incremental backup.

  ## Ship definition

  - [ ] Tools screen exposes an "Export" action that writes a versioned JSON
        via SAF.
  - [ ] FULL export round-trips: FULL export → full wipe → FULL import →
        identical profile/group/rule/setting state (verified by deep-equals).
  - [ ] SHARE export redacts all secret fields per an explicit allowlist, not
        a blocklist. Redaction is unit-tested against every protocol bean.
  - [ ] Import screen lets the user pick which subsets to restore (profiles /
        routing / settings); skipped subsets keep their current state.
  - [ ] Import on a schema version newer than the app surfaces a typed error;
        the current state is never partially overwritten.
  - [ ] Reset-all-settings action has a confirmation dialog and restarts the
        app via ProcessPhoenix-equivalent.
  - [ ] Share-sheet target for SHARE output lets users hand off the file.

  ## Child tasks

  - [[Add versioned backup JSON schema with redaction allowlist]]
  - [[Add SAF export action with FULL and SHARE variants]]
  - [[Add SAF import flow with selective restore]]
  - [[Add share-sheet intent for redacted SHARE backups]]
  - [[Add reset-all-settings action with confirmation and restart]]

  ## Dependencies

  - Depends on: [[Epic - NekoBox subscription and profile import]] — schema
    includes ProxyGroup/SubscriptionBean.
  - Depends on: [[Epic - Advanced routing rules and geoip enforcement]] —
    schema includes RuleEntity.

  ## Risks / open questions

  - Redaction allowlist must be per-protocol and per-field; one missed field
    leaks credentials. Add a failing test for every new bean introduced in
    [[Epic - Extended outbound protocol support]].
  - Sideways-compatible schema evolution is hard once shipped; pick field
    semantics carefully in v1.
  - Android's restore-after-reinstall UX: our export is explicit, but the
    system restore dialog after reinstall should still be a no-op since
    `allowBackup=false`. Verify.

  ## Links

  - [[ripdpi-android]]
  - Child issues: 5

- [ ] #task Epic - System HTTP proxy service mode #repo/RIPDPI #area/epic #status/backlog 🔼 [paperclip:POY-59]
  - Paperclip: POY-59 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, service-mode
  - **source:** `TaskNotes/Tasks/Epic - System HTTP proxy service mode.md`

  ## Goal

  Offer a no-VPN "system proxy" service mode for devices where the user wants
  a local SOCKS5/HTTP listener plus Android's system HTTP proxy handle, but
  does not want to hand RIPDPI the full TUN file descriptor. Matches NekoBox's
  `MODE_PROXY` alternative to `MODE_VPN`.

  ## Why now

  Two concrete deployments want this: (a) Android 10+ users who only need
  HTTP(S) coverage for a few apps that honor the system proxy, not full-device
  tunneling; (b) debug/diagnostics sessions where the operator wants to inspect
  traffic without the TUN taking over DNS. Today RIPDPI has only the TUN path.

  ## Key decisions

  - **Reuse the existing relay supervisor;** service mode is a different
    front-end (no TUN establish, no `vpn_protect` socket) over the same
    outbound dispatch.
  - **Mixed listener** (SOCKS5 + HTTP CONNECT on one port), same pattern as
    NekoBox `mixedPort`. Default 2080, user-configurable.
  - **System proxy injection is VPN-mode optional,** not a separate mode.
    Android 10+ can both establish the TUN and advertise a system HTTP proxy;
    this feature also benefits VPN mode users.
  - **No dual-mode.** Service picker in Settings: TUN VPN (default) or System
    Proxy. Exactly one runs per session.

  ## Scope

  - **In scope:** new `ProxyService` foreground service; mixed SOCKS5+HTTP
    inbound; service-mode picker in Settings; Android 10+ `setHttpProxy`
    integration for VPN mode; onboarding update to introduce the choice.
  - **Out of scope:** PAC file generation, authenticated SOCKS5 (unauthenticated
    local-only is sufficient; remote auth is a different security model), SOCKS4.

  ## Ship definition

  - [ ] Settings surface allows picking TUN VPN or System Proxy mode.
  - [ ] In System Proxy mode, a single foreground service on the mixed port
        answers SOCKS5 and HTTP CONNECT from local apps.
  - [ ] No TUN file descriptor is opened in System Proxy mode; `vpn_protect`
        socket is not required.
  - [ ] In VPN mode on Android 10+, an optional "also advertise HTTP proxy
        to system" toggle calls `setHttpProxy(ProxyInfo.buildDirectProxy(...))`
        on the builder.
  - [ ] Service-mode transitions (switching from TUN to proxy and back) shut
        down cleanly without leaking sockets or routes.
  - [ ] Diagnostics run in both modes; strategy probe works without a TUN
        present.

  ## Child tasks

  - [[Add mixed SOCKS5 and HTTP CONNECT inbound listener]]
  - [[Add ProxyService foreground service as alternative to TUN VPN]]
  - [[Add setHttpProxy integration for VpnService on Android 10+]]
  - [[Add service-mode picker to Settings and onboarding]]

  ## Dependencies

  - Feeds: [[Epic - Boot autostart and session persistence]] — boot autostart
    must resume the chosen service mode, not default to TUN.

  ## Risks / open questions

  - Many Android apps ignore the system HTTP proxy; be explicit in UX that
    System Proxy mode is lower-coverage than VPN.
  - HTTP CONNECT with TLS interception is out of scope; we proxy CONNECT
    tunnels only, no cleartext-to-TLS bridging.
  - Foreground-service-type must be `systemExempted` + `specialUse`; verify
    Play Store compatibility if a managed distribution channel is later added.

  ## Links

  - [[ripdpi-android]]
  - Child issues: 4

- [ ] #task Epic - VPN fleet testing matrix and release gates #repo/RIPDPI #area/android #status/backlog ⏫ [paperclip:POY-60]
  - Paperclip: POY-60 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-05-01
  - **dateModified:** 2026-05-01
  - **owner:** nikita
  - **area:** vps
  - **tags:** task, epic, vps, testing, qa, release
  - **source:** `TaskNotes/Tasks/Epic - VPN fleet testing matrix and release gates.md`

  ## Goal

  Build a testing matrix and release-gating system that classifies fleet and
  client failures as server failure, IP block, protocol block, CDN/path
  throttling, DNS/domain block, UDP/QUIC block, mobile whitelist mode, client
  regression, DNS/IPv6 leak, or kill-switch failure.

  ## Scope

  - In scope: test result schema, predeploy gates, postdeploy smoke tests,
    RU fixed/mobile probes, owned-node active-probe simulation, DNS/IPv6 and
    kill-switch gates, captive/whitelist tests, client compatibility regression,
    automated suite layout, and daily/weekly/release cadence.
  - Out of scope: scanning third-party infrastructure, storing live endpoints or
    tokens in TaskNotes, and replacing privacy-safe observability with raw logs.

  ## Status

  New cross-project QA epic derived from the 2026-05-01 fleet testing matrix
  brief.

  ## Child work

  - [[Define canonical fleet test result schema]]
  - [[Add predeploy validation gates for fleet configs]]
  - [[Add postdeploy smoke suite for fleet nodes]]
  - [[Add RU fixed and mobile network probe matrix]]
  - [[Add active-probe simulation suite for owned nodes]]
  - [[Add DNS IPv6 and kill-switch release gates]]
  - [[Add captive portal and whitelist-mode test cases]]
  - [[Add client compatibility regression matrix for fleet profiles]]
  - [[Create automated fleet test suite layout]]
  - [[Add fleet release gating and cadence policy]]

  ## Milestones

  - [ ] Every test records PASS, WARN, FAIL, or N/A with sanitized context.
  - [ ] Predeploy gates block invalid configs, secrets, unsafe certs, and public panels.
  - [ ] Postdeploy smoke tests cover service health, payload size, protocols,
        delivery, revocation, and old credential failure.
  - [ ] RU fixed/mobile matrix distinguishes IP, protocol, UDP, delivery, and
        whitelist failures.
  - [ ] DNS/IPv6/kill-switch gates are mandatory before production profile rollout.
  - [ ] Release policy defines no-ship and warn-only failures.

  ## Risks

  - Small health checks can hide Cloudflare/CDN 16 KB-like throttling.
  - A single Russian VPS probe is not representative of fixed and mobile user
    networks.
  - Active-probe simulation can become unsafe if it targets anything except owned
    nodes.

  ## Notes

  Live probe hosts, real endpoints, tester identities, and subscription tokens
  belong under `ops/live-infra/`, not in this epic.

  ## Links

  - [[vps-fleet-testing-matrix-2026-05-01]]
  - [[vps-proxy-fleet]]
  - [[ripdpi-android]]
  - [[Epic - Privacy-preserving fleet observability]]
  - [[Epic - Remove Cloudflare from critical path]]
  - [[Epic - Fail-closed Android VPN policy engine]]
  - Child issues: 4

- [ ] #task Epic - Xray VPN client mode #repo/RIPDPI #area/android #status/backlog ⏫ [paperclip:POY-61]
  - Paperclip: POY-61 · assigned to: unassigned
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-24
  - **dateModified:** 2026-04-24
  - **owner:** nikita
  - **area:** android
  - **tags:** task, epic, ripdpi, vpn, xray, libxray
  - **source:** `TaskNotes/Tasks/Epic - Xray VPN client mode.md`

  ## Goal

  Add a first remote VPN-client provider mode to RIPDPI by embedding
  `xray-core` through `libXray`, with VLESS/REALITY and XHTTP as the initial
  profile targets.

  ## Why now

  Direct-mode now has enough product framing to be honest when it cannot solve a
  network locally. The next practical fallback is a managed relay-provider path
  inside the same Android VPN UX, and Xray/libXray is the first provider the user
  wants to support.

  ## Key decisions

  - **Provider mode, not direct-mode replacement.** Xray-backed VPN client mode
    is a separate remote-relay provider that can be selected when direct-mode is
    unsuitable.
  - **Start with libXray.** Do not reimplement Xray protocol behavior in
    RIPDPI-native Rust for the first milestone; wrap the upstream library and
    isolate its unstable API behind a local adapter.
  - **Protect sockets before startup.** Xray sockets and DNS lookups must call
    Android `VpnService.protect(fd)` so the provider does not route itself back
    into the TUN device.
  - **Conservative tunnel path first.** Prefer the existing TUN-to-local-inbound
    routing path for the first internal build, while evaluating direct
    `SetTunFd` only after lifecycle and telemetry parity is proven.
  - **Secret-safe diagnostics.** Profile import, runtime errors, and diagnostic
    exports must redact UUIDs, private keys, server addresses, and live endpoints.

  ## Scope

  - **In scope:** libXray packaging, provider architecture, Xray JSON profile
    rendering/validation, managed Xray runtime lifecycle, Android socket
    protection, VPN tunnel routing through Xray, profile UX, telemetry,
    diagnostics, and regression coverage.
  - **Out of scope:** non-Xray provider SDKs, server provisioning automation,
    paid subscription/payment flows, and replacing the existing direct-mode
    native engine.

  ## Ship definition

  - [ ] RIPDPI can start Android VPN mode with Xray selected as the active
        provider.
  - [ ] At least VLESS/REALITY and XHTTP profile shapes validate and render to
        Xray JSON without leaking secrets.
  - [ ] Xray sockets are protected from the VPN loop, including DNS and listener
        paths.
  - [ ] Home, Diagnostics, and Settings show typed Xray provider state.
  - [ ] Lifecycle, config, protect-fd, telemetry, and smoke tests cover the
        first internal build.

  ## Child tasks

  **Architecture**
  - [[Define Xray VPN provider architecture]]
  - [[Package libXray for Android ABIs]]

  **Runtime path**
  - [[Render validated Xray client configs]]
  - [[Run Xray as managed VPN relay runtime]]
  - [[Bridge TUN traffic through Xray local inbound]]

  **Product and proof**
  - [[Add Xray profile UX and import flow]]
  - [[Surface Xray diagnostics and telemetry]]
  - [[Add Xray VPN client regression matrix]]

  Child tasks roll up via the TaskNotes relationships view on this note.

  ## Dependencies

  - Depends on: [[Recurring upstream watch for xray-core REALITY ECH XHTTP changes]]
    for version/deprecation tracking.
  - Coordinates with: [[Epic - Direct-mode diagnostic state machine]] because
    direct-mode negative verdicts should hand off to provider-mode suggestions
    without collapsing the two concepts.
  - Feeds: future release-pipeline work once Xray provider assets affect APK
    size, notices, and signed builds.

  ## Risks / open questions

  - `libXray` explicitly does not guarantee API stability, so the adapter must
    contain version-specific breakage.
  - Xray-core's release cadence can break profile assumptions faster than
    RIPDPI's normal app release cadence.
  - Direct `SetTunFd` may look simpler but could duplicate or weaken existing
    TUN telemetry, DNS interception, and shutdown behavior.
  - Geo assets, MPH cache files, and logs can increase APK/storage footprint or
    expose sensitive configuration if not scoped carefully.

  ## Links

  - [[ripdpi-android]]
  - [[ripdpi-android-xray-vpn-client-plan-2026-04-24]]
  - [[vless-reality-stack-research-2026-04-22]]
  - [[Recurring upstream watch for xray-core REALITY ECH XHTTP changes]]
  - Child issues: 8
