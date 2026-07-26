#!/usr/bin/env bash
# Render every @Preview composable in :app to PNG via compose-ai-tools.
#
# Architecture (yschimke/compose-ai-tools):
#   - The Gradle plugin (`ee.schimke.composeai.preview`, applied via the
#     `ripdpi.android.compose` convention plugin) only registers a marker
#     task: `:<module>:composePreviewApplied`.
#   - The actual `discoverPreviews` / `renderAllPreviews` tasks are supplied
#     by the `compose-preview` CLI's bundled Gradle init script.
#   - When the plugin is already declared in the build (it is here), the
#     CLI auto-inject skips its own classpath injection — so the two layers
#     don't fight.
#
# Output (verified with the pinned plugin at 0.16.59):
#   app/build/compose-previews/renders/   PNG of each preview
#   app/build/compose-previews/diffs/     visual diffs vs baseline
#   app/build/compose-previews/*.json     preview index / metadata
#
# These paths are gitignored. They are NEVER goldens — see
# .claude/rules/golden-bless-discipline.md.
#
# Usage:
#   scripts/render-compose-previews.sh            # render every @Preview
#   scripts/render-compose-previews.sh list       # list previews (no render)
#
# Install the CLI first (one-time, per dev machine):
#   curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash

set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

subcommand="${1:-render}"

if ! command -v compose-preview >/dev/null 2>&1; then
    cat >&2 <<'EOF'
error: `compose-preview` CLI not found on PATH.

The Gradle plugin alone only advertises support — it does not register the
render tasks. The CLI ships the Gradle init script that supplies them.

Install (one-time, macOS/Linux):
  curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash

Then re-run this script. See .claude/rules/compose-preview.md for the full contract.
EOF
    exit 127
fi

case "$subcommand" in
    render) compose-preview render ;;
    list)   compose-preview list ;;
    *) echo "unknown subcommand: $subcommand (use: render | list)" >&2; exit 64 ;;
esac

output_dir="app/build/compose-previews"
if [[ -d "$output_dir/renders" ]]; then
    count=$(find "$output_dir/renders" -name '*.png' -type f | wc -l | tr -d ' ')
    echo
    echo "preview output: $output_dir/renders ($count PNG)"
fi
