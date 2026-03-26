#!/usr/bin/env bash

set -euo pipefail

PACKAGE="com.poyka.ripdpi"
ACTIVITY="com.poyka.ripdpi/.activities.MainActivity"
AUTOMATION_ENABLED_KEY="com.poyka.ripdpi.automation.ENABLED"
AUTOMATION_ROUTE_KEY="com.poyka.ripdpi.automation.START_ROUTE"
ARCHIVE_PREFIX="ripdpi-diagnostics-"
SERIAL=""
PULL_ARCHIVE=0
LAUNCH_LOGS=1
ADB_BIN="${ADB:-adb}"
OUT_DIR=""

usage() {
    cat <<'EOF'
Usage: scripts/debug-runtime-issue.sh [options]

Clear logcat, optionally open the in-app logs screen, wait for reproduction, then
dump filtered runtime logs and optionally pull the latest support bundle.

Options:
  --serial <serial>     Target a specific adb device serial.
  --out-dir <dir>       Directory for captured artifacts.
  --package <package>   Override the Android package name.
  --activity <name>     Override the launch activity component.
  --pull-archive        Pull the latest support bundle from app cache.
  --no-launch-logs      Skip opening the logs screen through automation extras.
  --help                Show this help text.
EOF
}

while (($# > 0)); do
    case "$1" in
        --serial)
            SERIAL="${2:?missing serial value}"
            shift 2
            ;;
        --out-dir)
            OUT_DIR="${2:?missing output directory}"
            shift 2
            ;;
        --package)
            PACKAGE="${2:?missing package value}"
            shift 2
            ;;
        --activity)
            ACTIVITY="${2:?missing activity value}"
            shift 2
            ;;
        --pull-archive)
            PULL_ARCHIVE=1
            shift
            ;;
        --no-launch-logs)
            LAUNCH_LOGS=0
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [[ -z "$OUT_DIR" ]]; then
    OUT_DIR="${PWD}/build/runtime-debug/$(date +%Y%m%d-%H%M%S)"
fi

mkdir -p "$OUT_DIR"

adb_cmd=("$ADB_BIN")
if [[ -n "$SERIAL" ]]; then
    adb_cmd+=(-s "$SERIAL")
fi

run_adb() {
    "${adb_cmd[@]}" "$@"
}

echo "Output directory: $OUT_DIR"
echo "Clearing logcat buffer"
run_adb logcat -c

if (( LAUNCH_LOGS )); then
    echo "Opening logs screen through automation start-route extras"
    run_adb shell am start -n "$ACTIVITY" \
        --ez "$AUTOMATION_ENABLED_KEY" true \
        --es "$AUTOMATION_ROUTE_KEY" logs >/dev/null
fi

cat <<EOF
Reproduce the runtime issue now.

- Raw filtered logcat will be written to:
  $OUT_DIR/logcat-app.txt
- Native-only logcat will be written to:
  $OUT_DIR/logcat-native.txt
EOF

if (( PULL_ARCHIVE )); then
    cat <<EOF
- If you want the support bundle copied too, trigger "Share support bundle" in the app
  before continuing. The latest archive will be copied into:
  $OUT_DIR/
EOF
fi

read -r -p "Press Enter when reproduction is complete... " _

PID="$(run_adb shell pidof -s "$PACKAGE" 2>/dev/null | tr -d '\r')"

if [[ -n "$PID" ]]; then
    run_adb logcat -d -v threadtime --pid="$PID" >"$OUT_DIR/logcat-app.txt"
else
    echo "No live pid found for $PACKAGE; falling back to the full buffer" >&2
    run_adb logcat -d -v threadtime >"$OUT_DIR/logcat-app.txt"
fi

grep -E 'ripdpi-native|ripdpi-tunnel-native' "$OUT_DIR/logcat-app.txt" >"$OUT_DIR/logcat-native.txt" || true

{
    echo "package=$PACKAGE"
    echo "activity=$ACTIVITY"
    echo "pid=${PID:-none}"
    echo "captured_at=$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
} >"$OUT_DIR/capture-metadata.txt"

if (( PULL_ARCHIVE )); then
    archive_pattern="${ARCHIVE_PREFIX}*.zip"
    archive_name="$(
        run_adb shell run-as "$PACKAGE" sh -c \
            "cd cache/diagnostics-archives 2>/dev/null && ls -1t ${archive_pattern} 2>/dev/null | head -n1" |
            tr -d '\r'
    )"

    if [[ -n "$archive_name" ]]; then
        echo "Pulling support bundle: $archive_name"
        run_adb exec-out run-as "$PACKAGE" cat "cache/diagnostics-archives/$archive_name" >"$OUT_DIR/$archive_name"
    else
        echo "No support bundle found under cache/diagnostics-archives" >&2
    fi
fi

echo "Capture complete"
echo "Artifacts:"
find "$OUT_DIR" -maxdepth 1 -type f | sort
