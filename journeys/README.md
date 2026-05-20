# RIPDPI Journeys (Android CLI 1.0)

`journeys/` holds **Journeys** — natural-language, AI-agent-driven UI tests run by
the Android CLI 1.0 (`android journeys`). An agent (Claude Code, Gemini, …) reads
each journey, drives the app by vision and reasoning, and evaluates the
assertions against what it sees on the device.

These mirror the four `maestro/` smoke flows. Because the agent reasons about
goals rather than matching resource IDs, journeys are more resilient to layout
changes — but they require an AI agent in the loop to execute.

## Files

| Journey | Mirrors | Flow |
|---------|---------|------|
| `01-cold-launch-home.journey` | `maestro/01-cold-launch-home.yaml` | Cold launch → Home, primary mode visible |
| `02-settings-navigation.journey` | `maestro/02-settings-navigation.yaml` | Home → Settings → Advanced Settings |
| `03-advanced-settings-edit-save.journey` | `maestro/03-advanced-settings-edit-save.yaml` | Edit + save diagnostics history retention |
| `04-start-stop-configured-mode.journey` | `maestro/04-start-stop-configured-mode.yaml` | Toggle the primary local DPI-bypass mode |

## Running

Local / on-demand:

    scripts/ci/run-android-journeys-emulator.sh

CI: the `android-journeys` job in `.github/workflows/ci.yml` — opt-in, gated by
the `run_android_journeys` `workflow_dispatch` input or the `run-android-journeys`
PR label (the same gating pattern as `android-network-e2e`).

## Verification gates — confirm before this lane gates merges

1. **Journey schema.** Journey files use XML: a root `<journey>` element with a
   `<description>` and ordered `<step>` elements. The exact element/attribute
   schema follows the Android Studio "New > Journey Test" template. Confirm it
   against the template the installed Android CLI 1.0 emits (`android docs search
   'journeys'`, or the bundled `android-cli` skill) and adjust these files before
   relying on the lane.
2. **Run subcommand.** `scripts/ci/run-android-journeys-emulator.sh` invokes the
   `android` journey runner; confirm the exact subcommand and flags via
   `android journeys --help` after install.
3. **Agent in CI.** Journeys need an AI agent in the runner. Until one is wired
   into CI, the `android-journeys` job runs as opt-in scaffolding: the runner
   reports the missing-agent prerequisite and skips rather than failing the lane.

## Caveats

- The target app ID is passed to the runner through the `JOURNEYS_CUSTOM_APP_ID`
  environment variable (`com.poyka.ripdpi`), not inside the journey files.
- Starting a real VPN/proxy mode raises the system VPN-consent dialog. Journey
  `04` only toggles the mode control and verifies UI state — it does not assert a
  live tunnel. Conditional steps ("if a dialog appears …") are avoided because the
  Journeys engine does not fully support conditionals.
