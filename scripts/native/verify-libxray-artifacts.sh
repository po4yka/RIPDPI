#!/usr/bin/env bash
#
# verify-libxray-artifacts.sh — fail-closed verification gate for the packaged
# libXray Android artifact. Runs anywhere (no Go/gomobile needed) — pure shell.
#
# It FAILS (non-zero) on any of:
#   1. missing artifact directory / AAR / manifest               (absent state)
#   2. a missing ABI .so for any ABI named in gradle.properties   (incomplete)
#   3. version drift: manifest pins != gradle/libs.versions.toml  (drift)
#   4. native payload over the documented byte budget             (oversize)
#   5. a CANARY-channel manifest in a release-like invocation      (ship guard)
#
# All thresholds are documented in docs/native/libxray-packaging.md.
#
# Usage:
#   scripts/native/verify-libxray-artifacts.sh                # verify stable artifact
#   scripts/native/verify-libxray-artifacts.sh --release      # also reject canary channel
#   RIPDPI_XRAY_AAR_DIR=/path scripts/native/verify-libxray-artifacts.sh
#
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
gradle_props="$repo_root/gradle.properties"
versions_toml="$repo_root/gradle/libs.versions.toml"
art_dir="${RIPDPI_XRAY_AAR_DIR:-$repo_root/native/xray/artifacts}"

# Documented native payload budget for the libXray AAR (sum of all per-ABI
# .so payloads). xray-core + libXray + BoringSSL across 4 ABIs is large; the
# budget is intentionally generous but bounded so an accidental geo-asset
# bundle or debug-symbol leak fails CI. Override only with a documented bump in
# docs/native/libxray-packaging.md. Value is in bytes.
# Measured 2026-05-30 for libXray v26.3.27 / xray-core v1.260327.0, gomobile bind
# with -ldflags="-s -w" (stripped): ~126 MiB total across the 4 ABIs (~32 MiB each:
# armeabi-v7a 31.7, arm64-v8a 33.2, x86 32.0, x86_64 35.4). Budget = 160 MiB leaves
# ~27% headroom for xray-core growth while still catching an unstripped build
# (~178 MiB) or an accidental geo-asset bundle.
NATIVE_PAYLOAD_BUDGET_BYTES="${RIPDPI_XRAY_PAYLOAD_BUDGET_BYTES:-167772160}" # 160 MiB

release_mode=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --release)
            release_mode=1
            shift
            ;;
        -h | --help)
            sed -n '2,22p' "${BASH_SOURCE[0]}"
            exit 0
            ;;
        *)
            echo "ERROR: unknown argument: $1" >&2
            exit 64
            ;;
    esac
done

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

read_pin() {
    local key="$1" value
    value="$(grep -E "^${key} = " "$versions_toml" | head -n1 | sed -E 's/^[^=]*= *"([^"]*)".*/\1/')"
    [[ -n "$value" ]] || fail "pin '${key}' not found in $versions_toml"
    printf '%s' "$value"
}

read_gradle_prop() {
    local key="$1" value
    value="$(grep -E "^${key}=" "$gradle_props" | head -n1 | cut -d= -f2-)"
    [[ -n "$value" ]] || fail "gradle property '${key}' not found in $gradle_props"
    printf '%s' "$value"
}

# Minimal JSON string-field reader (manifest is emitted by build-libxray.sh).
# Anchors on the field key so it is correct for both multi-line and single-line
# JSON (a leading `.*` would greedily match the last quoted value on the line).
read_manifest_field() {
    local field="$1" file="$2" value
    value="$(grep -oE "\"${field}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$file" | head -n1 | sed -E "s/.*:[[:space:]]*\"([^\"]*)\"\$/\1/")"
    printf '%s' "$value"
}

# --- 1. absent state --------------------------------------------------------
[[ -d "$art_dir" ]] || fail "artifact directory not found: $art_dir (run scripts/native/build-libxray.sh)"
aar="$art_dir/libxray.aar"
manifest="$art_dir/libxray-artifact.json"
[[ -f "$aar" ]] || fail "AAR not found: $aar (run scripts/native/build-libxray.sh)"
[[ -f "$manifest" ]] || fail "artifact manifest not found: $manifest (run scripts/native/build-libxray.sh)"

# --- 3. version drift vs pins ----------------------------------------------
channel="$(read_manifest_field channel "$manifest")"
[[ -n "$channel" ]] || fail "manifest missing 'channel' field"

if [[ "$channel" == "canary" ]]; then
    expect_libxray="$(read_pin libxray-canary)"
    expect_xray="$(read_pin xray-core-canary)"
else
    expect_libxray="$(read_pin libxray)"
    expect_xray="$(read_pin xray-core)"
fi
expect_gomobile="$(read_pin gomobile)"
expect_ndk="$(read_gradle_prop ripdpi.nativeNdkVersion)"

m_libxray="$(read_manifest_field libxray "$manifest")"
m_xray="$(read_manifest_field xrayCore "$manifest")"
m_gomobile="$(read_manifest_field gomobile "$manifest")"
m_ndk="$(read_manifest_field ndkVersion "$manifest")"

[[ "$m_libxray" == "$expect_libxray" ]] ||
    fail "libXray version drift: manifest=$m_libxray pin=$expect_libxray"
[[ "$m_xray" == "$expect_xray" ]] ||
    fail "xray-core version drift: manifest=$m_xray pin=$expect_xray"
[[ "$m_gomobile" == "$expect_gomobile" ]] ||
    fail "gomobile version drift: manifest=$m_gomobile pin=$expect_gomobile"
[[ "$m_ndk" == "$expect_ndk" ]] ||
    fail "NDK version drift: manifest=$m_ndk pin=$expect_ndk"

# --- 5. canary ship guard ---------------------------------------------------
if [[ "$release_mode" -eq 1 && "$channel" == "canary" ]]; then
    fail "canary-channel libXray artifact must never ship in a release-like build"
fi

# --- 2. per-ABI .so presence + 4. byte budget -------------------------------
abis_csv="$(read_gradle_prop ripdpi.nativeAbis)"
work="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-xray-verify.XXXXXX")"
trap 'rm -rf "$work"' EXIT
unzip -qq -o "$aar" -d "$work" 2>/dev/null || fail "AAR is not a valid zip archive: $aar"

total_bytes=0
IFS=',' read -r -a abis <<<"$abis_csv"
for abi in "${abis[@]}"; do
    abi="$(printf '%s' "$abi" | tr -d '[:space:]')"
    [[ -z "$abi" ]] && continue
    # gomobile lays jni payloads under jni/<abi>/*.so inside the AAR.
    matches="$(find "$work" -type f -path "*/jni/${abi}/*.so" 2>/dev/null || true)"
    [[ -n "$matches" ]] || fail "no .so payload for ABI '${abi}' inside $aar"
    while IFS= read -r so; do
        [[ -z "$so" ]] && continue
        sz="$(wc -c <"$so" | tr -d '[:space:]')"
        total_bytes=$((total_bytes + sz))
    done <<<"$matches"
done

if [[ "$total_bytes" -gt "$NATIVE_PAYLOAD_BUDGET_BYTES" ]]; then
    fail "native payload ${total_bytes}B exceeds budget ${NATIVE_PAYLOAD_BUDGET_BYTES}B (see docs/native/libxray-packaging.md)"
fi

echo "OK: libXray artifact verified"
echo "  channel    $channel"
echo "  libXray    $m_libxray"
echo "  xray-core  $m_xray"
echo "  gomobile   $m_gomobile"
echo "  NDK        $m_ndk"
echo "  ABIs       $abis_csv"
echo "  payload    ${total_bytes}B / ${NATIVE_PAYLOAD_BUDGET_BYTES}B budget"
