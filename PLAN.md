# PLAN.md — RIPDPI UI/UX refactor

Anchor spec for a Codex `/goal`-driven refactor. Every milestone has a *verifiable*
acceptance criterion and a device-verification step. Codex must read this file, `DESIGN.md`,
`AGENTS.md`, and the navigation sources before starting any milestone.

## Objective

Rework the entire `:app` UI/UX around two axes:

1. **Three modes, surfaced as first-class:** Diagnose, Local bypass, VPN.
2. **Novice / expert split** implemented as *disclosure depth + default autonomy*, NOT a hard fork.

The connective tissue is a **diagnostics-first funnel**: run a check → per-network verdict →
one-tap apply the recommended traffic path. The verdict engine already exists
(`TRANSPARENT_WORKS / OWNED_STACK_ONLY / NO_DIRECT_SOLUTION / IP_BLOCK_SUSPECT`,
`DirectPathPolicyLearner`, fingerprint-keyed verdicts) — wire it into the UI.

## Non-goals / hard constraints (do NOT change)

- **No changes to `:core:engine`, `:core:service`, `native/` Rust crates, or any JNI contract.**
  This is a UI/UX refactor of `:app` (and read-only DTO/state surfaces it already consumes).
- Respect `VerifyEngineBoundaryClasspathTask`: `:app` must never gain a compile dependency on
  `:core:engine`. Reach engine capability only through `:core:service` as today.
- Do not change the `relay_kind` registry or any protocol semantics.
- Preserve Compose stability discipline: every UI state class stays `@Immutable`/`@Stable`,
  collections stay `ImmutableList`/persistent, flows stay `collectAsStateWithLifecycle`
  (zero plain `collectAsState`).
- Keep type-safe Navigation (`@Serializable` routes). Keep Hilt DI graph valid.
- Keep all 7 locales (en, ru, es, de, fr, fa, zh-rCN). Every new/renamed user string is added to
  every locale (machine translation acceptable as placeholder, flagged `TODO(loc)`).
- No new third-party dependency without explicit justification in the progress log.
- Keep min SDK and the existing build/quality gates green (detekt + compose-rules, lint).

## Definition of done (global stopping condition)

All milestones below are complete, AND:
- `./gradlew :app:assembleDebug detekt lint` is green.
- The full unit + Roborazzi suite passes; goldens regenerated for both light and dark.
- The Maestro journey suite under `maestro/` passes on the physically connected Pixel 7.
- No `Route` subtype is registered without a navigation entry point or an explicit allowlist entry.
- A short `docs/ui-refactor/PROGRESS.md` log exists with one entry per checkpoint.

## Information architecture (target)

Bottom nav, 4 tabs:

- **Status** (was Home) — three mode cards (Diagnose / Local bypass / VPN), each: state +
  one primary action + inline "why disabled" when gated. No permanent warning banners.
- **Diagnose** — the funnel (Simple) / probe matrix + replay + pcap + archive (Advanced).
- **Connection** (was Config + Mode editor) — configuration of the two traffic paths,
  Simple/Advanced layered.
- **Settings**.

## Persona model

- Global persona `Simple` | `Advanced`, set in onboarding, changeable in Settings.
- Simple never *hides state* — it only collapses controls and lets the app decide
  (Auto strategy, auto-apply verdict at HIGH confidence only).
- Every Advanced control remains reachable in Simple via an in-place `Advanced ▾` expander.
- At MEDIUM/LOW verdict confidence, Simple presents a choice instead of auto-applying.

## Terminology canon (unify all user-facing strings)

| Use exactly | Replaces |
| --- | --- |
| `Bypass strategy` | "Desync method", "Strategy chain" (as a label), "on-device packet strategy" |
| `Local bypass` | "Local DPI Bypass", "LOCAL DPI BYPASS" |
| `VPN` | "VPN with Remote Server" (card title may keep subtitle) |
| `Profile` | "preset", "mode", "configuration area" as the saved-bundle concept |
| `Starter profile` | the built-in Recommended/Proxy/Custom presets |

Internal class/identifier names may stay; this canon governs `strings.xml` and visible labels.

---

## Milestones

### M1 — Terminology + design-system unification
- Unify all user-facing strings to the canon above across every locale.
- Collapse the preset/mode/profile triplication into one `Profile` concept in the UI layer.
- **Accept:** `rg -i "desync method|strategy chain|VPN with Remote Server" app/src/main/res`
  returns only intentional subtitles; detekt + lint green; goldens regenerated.

### M2 — Navigation spine + dead-UI cleanup
- Implement the 4-tab IA. Re-cast Home→Status, Config→Connection.
- Wire or delete the orphaned routes (Logs, HandshakeTimeline, LatencyGraph, ThroughputGraph,
  StateMachine, OomRecovery, ReplayFailure, ProfileVariants, StrategyAb, StrategyImport, …).
  `Logs` MUST be wired (reachable from Diagnose-Advanced and Settings).
- Fix the duplicate `English` entry in the language picker.
- **Accept:** a unit test asserts every `Route` subtype is reachable via a `navigate`/`onOpen`
  call OR listed in an explicit `@Suppress`-style allowlist with a reason; a test asserts the
  language picker exposes distinct locales only; nav instrumented test green on Pixel 7.

### M3 — Persona system
- Add the global `Simple|Advanced` persona (DataStore-backed) + onboarding step + Settings toggle.
- Add the reusable `AdvancedSection` expander composable; gate Advanced controls through it.
- **Accept:** persona persists across process death (instrumented test on Pixel 7); Simple
  screens render with Advanced controls collapsed; Roborazzi goldens for both personas × both themes.

### M4 — Diagnostics-first funnel
- Simple Diagnose: one check button → plain-language verdict → one-tap apply that toggles the
  recommended path. Auto-apply only at HIGH confidence; otherwise show a choice.
- Surface per-network memory ("on this network, bypass worked last time").
- Advanced Diagnose: keep probe matrix, replay, pcap, archive export.
- **Accept:** Maestro flow `maestro/diagnose-apply.yaml` runs check → verdict → apply → path
  toggles ON, on the physical Pixel 7; verdict-confidence gating covered by unit tests.

### M5 — Local bypass screen
- Simple: on/off + "Strategy: Auto (recommended)" + "Re-test strategies" (triggers Strategy Probe
  transparently; if probe returns empty, show "couldn't pick", never a silent no-op).
- Advanced: chain editor, TTL, engine params, CLI overrides, Finalmask, manual strategy.
- Disambiguate precedence visual-chain vs raw-text vs CLI with an explicit on-screen note.
- **Accept:** Maestro flow toggles bypass and runs auto re-test on Pixel 7; precedence note present.

### M6 — VPN screen
- Simple: on/off + prominent "Add server" (paste / QR / scan); protocol inferred from the link;
  no protocol grid. Advanced: full protocol grid, Profile ID binding, chain relay, Finalmask.
- **Accept:** Maestro flow imports a sample `vless://`/`ss://` deep link → profile appears →
  appears in Simple list, on Pixel 7 (use the registered deep-link schemes; no live relay needed).

### M7 — Status (Home) redesign
- Three mode cards; kill permanent amber banners → single collapsible "Setup health" row;
  show lockdown warning only when VPN is active; inline "why disabled" on every gated control.
- **Accept:** Roborazzi goldens show no permanent banners in idle state; instrumented test asserts
  gated VPN "Enable" shows the inline reason.

### M8 — Settings + Detection + onboarding
- Restructure Settings; collapse Detection's contradictory metrics into one "Visibility" scale.
- Redesign onboarding: what-it-does → persona → permissions (just-in-time) → optional first
  diagnose; remove forced DNS-provider choice from onboarding.
- **Accept:** onboarding Maestro flow completes both personas on Pixel 7; Detection screen shows a
  single coherent metric (unit test on the mapping).

### M9 — Hardening + parity
- Full Maestro journey suite green on Pixel 7; Roborazzi goldens recorded for every screen in
  light AND dark; visual parity check; remove any `TODO(loc)` placeholders that have real strings.
- **Accept:** global Definition of done satisfied.

---

## Device-verification loop (physical Pixel 7 — NO emulator)

Run after every checkpoint. Discover exact Gradle task names first
(`./gradlew tasks --all | grep -iE "roborazzi|maestro"`); do not assume.

```
export RIPDPI_DEVICE="$(adb devices | awk 'NR==2{print $1}')"   # the connected Pixel 7
adb -s "$RIPDPI_DEVICE" shell getprop ro.product.model           # expect "Pixel 7"

./gradlew :app:installDebug
adb -s "$RIPDPI_DEVICE" shell pm path com.poyka.ripdpi            # must succeed

# UI flow on the device:
maestro --device "$RIPDPI_DEVICE" test maestro/<flow>.yaml

# Visual check in BOTH themes:
adb -s "$RIPDPI_DEVICE" shell cmd uimode night no  && adb -s "$RIPDPI_DEVICE" exec-out screencap -p > /tmp/ripdpi-<screen>-light.png
adb -s "$RIPDPI_DEVICE" shell cmd uimode night yes && adb -s "$RIPDPI_DEVICE" exec-out screencap -p > /tmp/ripdpi-<screen>-dark.png

# Host goldens:
./gradlew :app:recordRoborazziDebug  -Pripdpi.includeRoborazziUnitTests=true   # regenerate
./gradlew :app:verifyRoborazziDebug  -Pripdpi.includeRoborazziUnitTests=true   # check
```

Verify UI, navigation, state, and form behaviour only — not live tunnel throughput. FLAG_SECURE
screens (Strategy Config, Biometric) render black to `screencap`; verify those via Roborazzi
goldens / Compose previews instead.

## Progress log

Append one entry per checkpoint to `docs/ui-refactor/PROGRESS.md`:
`<milestone> · <checkpoint> · verified-by:<command/flow> · status:<done|blocked> · notes`.
