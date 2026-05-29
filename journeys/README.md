# RIPDPI Journeys (Android CLI 1.0)

`journeys/` holds **Journeys** — natural-language, AI-agent-driven UI tests. An
agent (the `android-test-runner` agent, Gemini, or any agent with the bundled
`android-cli` skill) reads each journey, drives the app by vision and reasoning,
and evaluates the assertions against what it sees on the device.

These mirror the first four `maestro/` smoke flows. Because the agent reasons about
goals rather than matching resource IDs, journeys are more resilient to layout
changes — but they require an AI agent to execute.

## There is no `android journeys` command

Android CLI 1.0 (`1.0.x`) exposes no `journeys` subcommand. A journey is run by
an agent that loops over the CLI's primitives:

| Step | Command |
|------|---------|
| Capture an annotated screenshot | `android screen capture --annotate -o shot.png` |
| Convert a labeled element to tap coordinates | `android screen resolve --screenshot=shot.png --string="input tap #N"` |
| Inspect the UI tree (for assertions) | `android layout --pretty` |
| Deploy the app | `android run --apks=<apk>` |

The agent reads a `.journey` file, performs each step, and evaluates each
assertion from the screenshots and layout tree.

## Files

| Journey | Mirrors | Flow |
|---------|---------|------|
| `01-cold-launch-home.journey` | `maestro/01-cold-launch-home.yaml` | Cold launch → Home, primary mode visible |
| `02-settings-navigation.journey` | `maestro/02-settings-navigation.yaml` | Home → Settings → Advanced Settings |
| `03-advanced-settings-edit-save.journey` | `maestro/03-advanced-settings-edit-save.yaml` | Edit + save diagnostics history retention |
| `04-start-stop-configured-mode.journey` | `maestro/04-start-stop-configured-mode.yaml` | Toggle the primary local DPI-bypass mode |

The `.journey` files are XML (root `<journey>` with a `<description>` and ordered
`<step>` elements), mirroring the Android Studio "New > Journey Test" template
for familiarity. No CLI command parses them — they are agent-consumed specs, so
the schema is for human/agent legibility only.

## Running

`scripts/ci/run-android-journeys-emulator.sh` **prepares** a device: it installs
the app, smoke-tests the journey primitives (`screen capture`, `layout`), and
lists the journeys. It does not — cannot — perform the journeys itself.

To actually execute the journeys, hand off to an agent:

    scripts/ci/run-android-journeys-emulator.sh   # prepare the device
    # then ask the android-test-runner agent to run the journeys/ flows

CI: the `android-journeys` job in `.github/workflows/ci.yml` runs the prepare
step — opt-in, gated by the `run_android_journeys` `workflow_dispatch` input or
the `run-android-journeys` PR label (the gating pattern of `android-network-e2e`).
Full unattended journey execution in CI needs an agent wired into the runner;
that is a tracked follow-up, not part of this lane today.

## Caveats

- The target app ID is passed via the `JOURNEYS_CUSTOM_APP_ID` environment
  variable (`com.poyka.ripdpi`), not inside the journey files.
- Starting a real VPN/proxy mode raises the system VPN-consent dialog. Journey
  `04` only toggles the mode control and verifies UI state — it does not assert a
  live tunnel. Conditional steps ("if a dialog appears …") are avoided because the
  Journeys engine does not fully support conditionals.
