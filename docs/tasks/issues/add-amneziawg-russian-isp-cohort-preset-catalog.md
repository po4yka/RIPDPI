---
title: Add AmneziaWG Russian ISP cohort preset catalog
type: task
status: backlog
area: data
priority: high
owner: unassigned
parent: epic-ripdpi-vpn-deploy-fleet-compatibility
blocks: []
blocked_by: []
created: 2026-05-14
updated: 2026-05-14
---

- [ ] #task Add AmneziaWG Russian ISP cohort preset catalog #repo/RIPDPI #area/data #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-amneziawg-russian-isp-cohort-preset-catalog`
- **Verify:** `./gradlew :core:data:testDebugUnitTest validateAwgCohortCatalog`
- **Scope (only modify these + this file + the ledger):** `app/**`, `core/data/assets/**`, `core/data/src/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Objective

Ship the deployer's known AmneziaWG obfuscation cohort profiles as a
**read-only, hot-updatable in-app data catalog**, so a user picks
"I'm on Rostelecom South" instead of hand-typing
`Jc/Jmin/Jmax/S1/S2/H1..H4`. The numbers are server-coordinated and
must not be user-editable when a preset is selected.

## Context

### Why presets, not free entry

AmneziaWG obfuscation parameters must **exactly match** what the
server sends — they are server-coordinated, and the strategy learner
must not vary them
([[Add strategy-pack compatibility hints for AmneziaWG servers]]).
The deployer tunes a fixed tuple per Russian ISP DPI signature and
documents the set in `ripdpi-vpn-deploy/docs/AWG-COHORTS.md`, with
per-cohort vars under
`ripdpi-vpn-deploy/ansible/roles/amneziawg/vars/cohorts/`. A new user
provisioned for a specific cohort has no way to know which numbers
to enter; mirroring the deployer's catalog removes that guesswork.

### Why a data asset, not code

RU ISPs retune classifiers; the deployer occasionally re-tunes a
cohort. The catalog must change without a Kotlin code change — ship
it as `core/data/assets/awg-cohorts.json`, parsed at runtime, with a
build-time validator that the JSON matches the AWG config model.

### Localization constraint

Per the repo 7-locale rule (`CLAUDE.md`), the asset JSON references
**string-resource keys** for `displayName` / `description` — never
literal text. Every key lands in all 7 `strings.xml` files
(en, ru, es, de, fr, fa, zh-CN) in the same commit, or
`MissingTranslation` fails CI.

## TDD workflow

Implement strictly test-first per the epic TDD policy.

1. **Red** — author these and confirm each fails before
   implementation:
   - `core/data/src/test/kotlin/.../AwgCohortCatalogLoadTest.kt` —
     loads `awg-cohorts.json`; asserts the six presets
     (`default`, `rtk_south`, `mts`, `beeline`, `megafon`,
     `home_isp_broad`) parse and validate against the AWG config
     model. *Fails: no catalog, no loader.*
   - same file, **malformed-JSON case** — corrupt asset; assert the
     app boots with an **empty** catalog and the picker shows only
     "Custom", no crash. *Fails: parse exception propagates.*
   - `core/data/src/test/kotlin/.../AwgCohortPresetApplyTest.kt` —
     applying each preset to a profile form rewrites exactly the
     obfuscation fields and **leaves server / port / keys
     untouched**. *Fails: no apply logic.*
   - `core/data/src/test/kotlin/.../AwgCohortMatchOnImportTest.kt` —
     an imported `.conf` whose obfuscation params byte-match
     `rtk_south` is tagged `rtk_south`; a `.conf` one byte off is
     tagged `Custom`. *Fails: no match logic.*
   - a Gradle build-time validator task (`validateAwgCohortCatalog`)
     that fails the build on a missing field or unknown key in the
     JSON. *Fails: task does not exist.*
   - a CI diff assertion that the catalog's numeric values match
     `ripdpi-vpn-deploy/docs/AWG-COHORTS.md` (lives in the fleet
     golden suite). *Fails: no cross-repo check.*
2. **Confirm failures** — record observed messages in the Work log.
3. **Green** — add the asset, the loader, the apply logic, the
   match-on-import logic, the Gradle validator — minimal to pass.
4. **Refactor** — fold the match-on-import logic into the existing
   `.conf` parser path rather than bolting on a second pass; re-run,
   stay green.
5. **Verify** — run `## Completion criteria` commands; attach output.

## Acceptance criteria

- [ ] `core/data/assets/awg-cohorts.json` ships with six presets,
    numeric values mirroring `ripdpi-vpn-deploy/docs/AWG-COHORTS.md`
    as of the implementation date:
    - `default` — `Jc=4, Jmin=40, Jmax=70, S1=50, S2=100`,
      `H1..H4` = random 32-bit unsigned
    - `rtk_south` — `Jc=4, Jmin=10, Jmax=50, S1=0, S2=0`,
      `H1=1, H2=2, H3=3, H4=4`
    - `mts`, `beeline`, `megafon` — mobile-carrier cohorts
    - `home_isp_broad` — broad home-ISP fallback
- [ ] Each preset has `id`, `displayName` (string-resource key),
    `description` (string-resource key), and the full obfuscation
    field set; asset JSON holds **no literal localized text**.
- [ ] All new string keys exist in all 7 locale `strings.xml` files.
- [ ] A Gradle task (`validateAwgCohortCatalog`) parses the JSON,
    validates every preset against the AWG Kotlin config model, and
    **fails the build** on a missing field or unknown key.
- [ ] The AWG profile editor exposes a "Cohort preset" picker above
    the raw obfuscation fields:
    - picking a preset writes its numbers into the fields and marks
      them read-only with a "from preset" badge;
    - picking "Custom" frees the fields;
    - the picker changes **only** obfuscation params — server,
      port, and keys are preserved.
- [ ] The `.conf` subscription-import path tags a profile with a
    preset ID when its obfuscation params byte-match a known
    preset; otherwise it is "Custom".
- [ ] The catalog is loaded at app start; a documented hook marks
    where a future remote refresh would plug in (the refresh itself
    is out of scope).
- [ ] Malformed catalog JSON → app boots with an empty catalog,
    picker shows only "Custom", no crash.

## Test plan

| Layer | File | Cases |
|---|---|---|
| Kotlin unit | `AwgCohortCatalogLoadTest.kt` | six presets load + validate; malformed JSON → empty catalog, no crash |
| Kotlin unit | `AwgCohortPresetApplyTest.kt` | each preset rewrites obfuscation fields; server/port/keys untouched |
| Kotlin unit | `AwgCohortMatchOnImportTest.kt` | exact match → preset tag; one byte off → Custom |
| Build / CI | `validateAwgCohortCatalog` Gradle task | missing field fails build; unknown key fails build |
| Golden-file | fleet suite | catalog values diffed against `docs/AWG-COHORTS.md` |
| Instrumented | `app/src/androidTest/.../AwgCohortPickerUiTest.kt` | picker writes fields, "from preset" badge, "Custom" frees fields |

## Completion criteria

`#status/done` only when **every** item holds, with evidence in the
`## Work log`:

- [ ] All `## Acceptance criteria` checkboxes checked.
- [ ] All test files + the Gradle validator exist, written
    **before** implementation (red-then-green confirmed in the Work
    log), and pass.
- [ ] `./gradlew :core:data:testDebugUnitTest validateAwgCohortCatalog`
    green — output attached.
- [ ] Instrumented picker test green on an emulator — output
    attached.
- [ ] `./gradlew lintDebug` clean; `MissingTranslation` green (all 7
    locales carry the new keys).
- [ ] The fleet-suite cross-repo diff against
    `ripdpi-vpn-deploy/docs/AWG-COHORTS.md` is green.
- [ ] **Manual walkthrough**, recorded in the Work log: fresh
    install → "Add AmneziaWG profile" → paste server/port/keys →
    pick "RTK South" → connect, without typing a `Jc` value.
- [ ] Reviewed by a separate `code-reviewer` pass.
- [ ] `## Work log` added: changed files, catalog source date,
    test output, residual risk (cohort drift).

## Source references

- Deployer cohort docs:
  `ripdpi-vpn-deploy/docs/AWG-COHORTS.md`
- Deployer cohort vars:
  `ripdpi-vpn-deploy/ansible/roles/amneziawg/vars/cohorts/`
- Deployer per-cohort param injection:
  `ripdpi-vpn-deploy/ansible/roles/amneziawg/templates/awg0.conf.j2:18-26`
- AWG editor (the screen this picker plugs into):
  [[Add AmneziaWG profile editor screen with obfuscation fields]]
- Strategy-learner exclusion:
  [[Add strategy-pack compatibility hints for AmneziaWG servers]]

## Links

- [[Epic - ripdpi-vpn-deploy fleet compatibility]]
- [[Epic - AmneziaWG outbound support]]
- [[Add AmneziaWG profile editor screen with obfuscation fields]]
- [[Add AmneziaWG Kotlin config model and dot-conf parser extensions]]
- [[Add strategy-pack compatibility hints for AmneziaWG servers]]
- [[Add ripdpi-vpn-deploy fleet compatibility golden-file tests]]
