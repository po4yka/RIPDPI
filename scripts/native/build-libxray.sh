#!/usr/bin/env bash
# Build the pinned patched Android AAR; publish only after native tests and verification.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
out_dir="${RIPDPI_XRAY_AAR_DIR:-$repo_root/native/xray/artifacts}"
abis_override=""
check_only=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --abis) abis_override="${2:?missing ABI list}"; shift 2 ;;
        --channel)
            [[ "${2:-}" == stable ]] || { echo 'Only the reviewed stable patch set can be built.' >&2; exit 64; }
            shift 2 ;;
        --check-toolchain) check_only=1; shift ;;
        -h|--help)
            echo 'build-libxray.sh [--abis arm64-v8a,...] [--check-toolchain]'
            echo 'RIPDPI_XRAY_AAR_DIR must be a new or empty output directory.'
            exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 64 ;;
    esac
done
read_pin() { sed -nE "s/^$1 = \"([^\"]*)\".*/\1/p" "$repo_root/gradle/libs.versions.toml"; }
read_property() { sed -n "s/^$1=//p" "$repo_root/gradle.properties"; }
libxray_ref="$(read_pin libxray)"
gomobile_ref="$(read_pin gomobile)"
ndk_version="$(read_property ripdpi.nativeNdkVersion)"
min_sdk="$(read_property ripdpi.minSdk)"
abis_csv="${abis_override:-$(read_property ripdpi.nativeAbis)}"
for tool in go gomobile gobind git python3; do
    command -v "$tool" >/dev/null || { echo "Required tool unavailable: $tool" >&2; exit 69; }
done
[[ "$(go env GOSUMDB)" != off ]] || { echo 'Go checksum verification must remain enabled.' >&2; exit 69; }
ndk_home="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[[ -f "$ndk_home/source.properties" ]] || { echo 'ANDROID_NDK_HOME must identify the pinned NDK.' >&2; exit 69; }
grep -Eq "^Pkg.Revision[[:space:]]*=[[:space:]]*$ndk_version$" "$ndk_home/source.properties" || {
    echo "NDK version must be $ndk_version" >&2; exit 69;
}
case "$(go env GOHOSTARCH)" in
    amd64) ;;
    *) echo 'Use an amd64 Go toolchain (Rosetta on Apple Silicon) for gomobile Android.' >&2; exit 69 ;;
esac
for tool in gomobile gobind; do
    go version -m "$(command -v "$tool")" | grep -F "v$gomobile_ref" >/dev/null || {
        echo "$tool does not match the pinned gomobile module" >&2; exit 69;
    }
done
python3 -c 'import sys; sys.path.insert(0,sys.argv[1]); from libxray_artifacts import policy; policy()' "$repo_root/scripts/native"
echo "Toolchain OK: $(go version); libXray=$libxray_ref; gomobile=$gomobile_ref; NDK=$ndk_version; ABIs=$abis_csv"
[[ "$check_only" == 0 ]] || exit 0
if [[ "${BUILD_GATE_HELD:-0}" != 1 ]] && command -v build-gate >/dev/null; then
    exec build-gate -- bash "$0" --abis "$abis_csv"
fi
# gomobile starts one Go process per ABI; each inherits a single compiler job.
# Android environments preserve these variables (unlike gomobile's Apple tags).
export GOMAXPROCS=1 GOFLAGS=-p=1
parent_dir="$(dirname "$out_dir")"
mkdir -p "$parent_dir"
[[ ! -L "$out_dir" ]] || { echo 'Refusing symlink output directory.' >&2; exit 73; }
if [[ -e "$out_dir" ]]; then
    [[ -d "$out_dir" && -z "$(ls -A "$out_dir")" ]] || {
        echo 'Output already contains files; choose a fresh RIPDPI_XRAY_AAR_DIR.' >&2; exit 73;
    }
fi
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-libxray-build.XXXXXX")"
stage_dir="$(mktemp -d "$parent_dir/.libxray-stage.XXXXXX")"
trap 'rm -rf "$work_dir" "$stage_dir"' EXIT
source_url="${RIPDPI_LIBXRAY_SOURCE_REPOSITORY:-https://github.com/XTLS/libXray.git}"
git clone --depth 1 --branch "$libxray_ref" "$source_url" "$work_dir/libXray"
python3 "$repo_root/scripts/native/prepare-libxray.py" "$work_dir/libXray"
python3 "$repo_root/scripts/native/test-libxray-protection.py" "$work_dir/libXray" "$work_dir/xray-core" --grpc "$work_dir/grpc"
targets=()
IFS=',' read -r -a abis <<<"$abis_csv"
for abi in "${abis[@]}"; do
    case "$abi" in
        armeabi-v7a) targets+=(android/arm) ;;
        arm64-v8a) targets+=(android/arm64) ;;
        x86) targets+=(android/386) ;;
        x86_64) targets+=(android/amd64) ;;
        *) echo "Unsupported ABI: $abi" >&2; exit 65 ;;
    esac
done
targets_csv="$(IFS=','; echo "${targets[*]}")"
(
    cd "$work_dir/libXray"
    gomobile bind -target="$targets_csv" -androidapi "$min_sdk" -trimpath \
        -ldflags='-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384' \
        -o "$stage_dir/libxray.aar" github.com/xtls/libxray
)
python3 "$repo_root/scripts/native/libxray_artifacts.py" "$stage_dir" --abis "$abis_csv" --create "$work_dir/libXray"
# The final directory never contains an unverified AAR, even after build failure.
[[ ! -d "$out_dir" ]] || rmdir "$out_dir"
mv "$stage_dir" "$out_dir"
echo "Published verified libXray artifact: $out_dir"
