#!/usr/bin/env bash
# UNVERIFIED IN CI: requires Go + gomobile toolchain (absent in this sandbox)
#
# build-libxray.sh — reproducibly build the libXray Android `.aar` (per-ABI
# `.so` payloads) with gomobile, pinned to the versions in
# gradle/libs.versions.toml.
#
# This script is intentionally fail-closed: it refuses to run when Go or the
# gomobile/gobind toolchain is missing, and it never produces a partial or
# "stub" artifact that could be mistaken for a real build. The artifact it
# writes is consumed by `scripts/native/verify-libxray-artifacts.sh` and wired
# into :core:engine via the `ripdpi.prebuiltXrayAarDir` Gradle input.
#
# ABI / SDK / NDK values are read from gradle.properties — never hardcoded here.
# (ripdpi.nativeAbis, ripdpi.minSdk, ripdpi.nativeNdkVersion)
#
# Usage:
#   scripts/native/build-libxray.sh                 # build STABLE pins, full ABI set
#   scripts/native/build-libxray.sh --channel canary  # build CANARY pins (never ship)
#   scripts/native/build-libxray.sh --abis arm64-v8a   # restrict ABIs for local iteration
#   scripts/native/build-libxray.sh --check-toolchain  # only verify the toolchain, then exit
#
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
gradle_props="$repo_root/gradle.properties"
versions_toml="$repo_root/gradle/libs.versions.toml"
out_dir="${RIPDPI_XRAY_AAR_DIR:-$repo_root/native/xray/artifacts}"

channel="stable"
abis_override=""
check_toolchain_only=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --channel)
            channel="${2:-}"
            shift 2
            ;;
        --abis)
            abis_override="${2:-}"
            shift 2
            ;;
        --check-toolchain)
            check_toolchain_only=1
            shift
            ;;
        -h | --help)
            sed -n '2,30p' "${BASH_SOURCE[0]}"
            exit 0
            ;;
        *)
            echo "ERROR: unknown argument: $1" >&2
            exit 64
            ;;
    esac
done

case "$channel" in
    stable | canary) ;;
    *)
        echo "ERROR: --channel must be 'stable' or 'canary' (got: $channel)" >&2
        exit 64
        ;;
esac

# --- read a pin from libs.versions.toml -------------------------------------
read_pin() {
    local key="$1"
    local value
    value="$(grep -E "^${key} = " "$versions_toml" | head -n1 | sed -E 's/^[^=]*= *"([^"]*)".*/\1/')"
    if [[ -z "$value" ]]; then
        echo "ERROR: pin '${key}' not found in $versions_toml" >&2
        exit 70
    fi
    printf '%s' "$value"
}

# --- read a property from gradle.properties (never hardcode SDK/NDK/ABI) ----
read_gradle_prop() {
    local key="$1"
    local value
    value="$(grep -E "^${key}=" "$gradle_props" | head -n1 | cut -d= -f2-)"
    if [[ -z "$value" ]]; then
        echo "ERROR: gradle property '${key}' not found in $gradle_props" >&2
        exit 70
    fi
    printf '%s' "$value"
}

if [[ "$channel" == "canary" ]]; then
    libxray_ref="$(read_pin libxray-canary)"
    xray_core_ref="$(read_pin xray-core-canary)"
else
    libxray_ref="$(read_pin libxray)"
    xray_core_ref="$(read_pin xray-core)"
fi
gomobile_ref="$(read_pin gomobile)"

ndk_version="$(read_gradle_prop ripdpi.nativeNdkVersion)"
min_sdk="$(read_gradle_prop ripdpi.minSdk)"
if [[ -n "$abis_override" ]]; then
    abis_csv="$abis_override"
else
    abis_csv="$(read_gradle_prop ripdpi.nativeAbis)"
fi

# --- fail-closed toolchain detection ----------------------------------------
fail_toolchain() {
    cat >&2 <<EOF
ERROR: $1

libXray packaging requires a Go + gomobile toolchain, which is NOT installed.
This sandbox/CI lane cannot build the artifact; install locally and re-run:

  1. Install Go (matching upstream libXray go.mod, currently go1.24+):
       https://go.dev/dl/

  2. Install the gomobile bind toolchain pinned to:
       golang.org/x/mobile@${gomobile_ref}
       go install golang.org/x/mobile/cmd/gomobile@${gomobile_ref}
       go install golang.org/x/mobile/cmd/gobind@${gomobile_ref}
       gomobile init

  3. Ensure ANDROID_NDK_HOME points at NDK ${ndk_version}
       (the version pinned by ripdpi.nativeNdkVersion in gradle.properties).

  4. Re-run: scripts/native/build-libxray.sh

Never bypass this check by hand-placing a .so/.aar — the verify script will
reject an artifact whose manifest does not match the gradle/libs.versions.toml
pins.
EOF
    exit 69
}

command -v go >/dev/null 2>&1 || fail_toolchain "Go toolchain ('go') not found on PATH."
command -v gomobile >/dev/null 2>&1 || fail_toolchain "gomobile not found on PATH."
command -v gobind >/dev/null 2>&1 || fail_toolchain "gobind not found on PATH."

ndk_home="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$ndk_home" || ! -d "$ndk_home" ]]; then
    fail_toolchain "ANDROID_NDK_HOME / ANDROID_NDK_ROOT is unset or not a directory."
fi

echo "libXray packaging toolchain OK:"
echo "  go        $(go version)"
echo "  gomobile  $(command -v gomobile)"
echo "  NDK       $ndk_home (pinned: $ndk_version)"
echo "  channel   $channel"
echo "  libXray   $libxray_ref"
echo "  xray-core $xray_core_ref"
echo "  gomobile  $gomobile_ref"
echo "  minSdk    $min_sdk"
echo "  ABIs      $abis_csv"

if [[ "$check_toolchain_only" -eq 1 ]]; then
    echo "Toolchain check passed (--check-toolchain); not building."
    exit 0
fi

# --- map RIPDPI Android ABIs to gomobile target identifiers -----------------
gomobile_target_for_abi() {
    case "$1" in
        armeabi-v7a) printf 'android/arm' ;;
        arm64-v8a) printf 'android/arm64' ;;
        x86) printf 'android/386' ;;
        x86_64) printf 'android/amd64' ;;
        *)
            echo "ERROR: unsupported ABI for gomobile: $1" >&2
            exit 65
            ;;
    esac
}

IFS=',' read -r -a abis <<<"$abis_csv"
targets=()
for abi in "${abis[@]}"; do
    abi="$(printf '%s' "$abi" | tr -d '[:space:]')"
    [[ -z "$abi" ]] && continue
    targets+=("$(gomobile_target_for_abi "$abi")")
done
if [[ "${#targets[@]}" -eq 0 ]]; then
    echo "ERROR: no ABIs resolved from '$abis_csv'." >&2
    exit 65
fi
targets_csv="$(
    IFS=','
    printf '%s' "${targets[*]}"
)"

# --- checkout libXray at the pinned ref into a reproducible work dir ---------
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-libxray.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT
echo "Cloning libXray @ ${libxray_ref} into $work_dir ..."
git clone --depth 1 --branch "$libxray_ref" \
    https://github.com/XTLS/libXray.git "$work_dir/libXray" 2>/dev/null ||
    git -C "$work_dir" clone https://github.com/XTLS/libXray.git libXray

(
    cd "$work_dir/libXray"
    git fetch --depth 1 origin "$libxray_ref" 2>/dev/null || true
    git checkout "$libxray_ref"

    # Enforce the xray-core pin: libXray vendors a specific xray-core release.
    # Fail if the go.mod pin diverges from gradle/libs.versions.toml.
    if grep -qE 'github.com/xtls/xray-core' go.mod; then
        gomod_xray="$(grep -E 'github.com/xtls/xray-core' go.mod | head -n1 | awk '{print $2}' | sed 's/^v//')"
        if [[ "$channel" == "stable" && "$gomod_xray" != "$xray_core_ref" ]]; then
            echo "ERROR: libXray ${libxray_ref} vendors xray-core ${gomod_xray}, but the pin is ${xray_core_ref}." >&2
            echo "       Bump 'xray-core' in gradle/libs.versions.toml to match, in the same PR." >&2
            exit 71
        fi
    fi

    mkdir -p "$out_dir"
    aar_path="$out_dir/libxray.aar"
    echo "Building gomobile AAR for targets: $targets_csv"
    gomobile bind \
        -target="$targets_csv" \
        -androidapi "$min_sdk" \
        -o "$aar_path" \
        ./...

    # Emit a manifest the verify script will diff against the pins.
    manifest="$out_dir/libxray-artifact.json"
    sha="$(git rev-parse HEAD)"
    {
        printf '{\n'
        printf '  "channel": "%s",\n' "$channel"
        printf '  "libxray": "%s",\n' "$libxray_ref"
        printf '  "libxrayCommit": "%s",\n' "$sha"
        printf '  "xrayCore": "%s",\n' "$xray_core_ref"
        printf '  "gomobile": "%s",\n' "$gomobile_ref"
        printf '  "minSdk": "%s",\n' "$min_sdk"
        printf '  "ndkVersion": "%s",\n' "$ndk_version"
        printf '  "abis": "%s",\n' "$abis_csv"
        printf '  "aar": "%s"\n' "$(basename "$aar_path")"
        printf '}\n'
    } >"$manifest"
    echo "Wrote $aar_path"
    echo "Wrote $manifest"
)

echo "libXray packaging complete. Verify with:"
echo "  scripts/native/verify-libxray-artifacts.sh"
