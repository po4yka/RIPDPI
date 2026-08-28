# Reproducible container lane for the libXray Android ABI packaging.
# Builds the exact toolchain scripts/native/build-libxray.sh needs:
#   Go 1.26 (libXray v26.x requires Go >= 1.26.2) + Android SDK + NDK + gomobile.
# Matches RIPDPI gradle.properties: compileSdk=36, NDK=29.0.14206865.
#
# HOST ARCHITECTURE: build and run this image on x86_64. The Android NDK ships
# only an x86_64 host toolchain (linux-x86_64 / darwin-x86_64); `gomobile bind`
# runs the host clang, so on arm64 it panics "unsupported GOARCH: arm64". On
# Apple Silicon, run under amd64 emulation (see docs/native/libxray-packaging.md
# § Host architecture). Verified end-to-end (toolchain + libXray v26.3.27 clone +
# xray-core drift gate) on 2026-05-30; the cross-compile itself needs x86_64.
#
# Usage:
#   docker build --platform linux/amd64 -f scripts/native/libxray-build.Dockerfile -t ripdpi/libxray-build .
#   docker run --rm --platform linux/amd64 -v "$PWD":/work:ro -e RIPDPI_XRAY_AAR_DIR=/artifacts \
#     ripdpi/libxray-build -c 'cd /work && bash scripts/native/build-libxray.sh \
#       && bash scripts/native/verify-libxray-artifacts.sh'
FROM golang:1.26-bookworm

ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708
ARG ANDROID_PLATFORM=android-36
ARG ANDROID_BUILD_TOOLS=36.0.0
ARG NDK_VERSION=29.0.14206865

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk

# Retry wrapper so transient network blips don't fail the whole build.
RUN printf '#!/bin/sh\nn=0; until "$@"; do n=$((n+1)); [ $n -ge 5 ] && exit 1; echo "retry $n: $*"; sleep 5; done\n' > /usr/local/bin/retry \
    && chmod +x /usr/local/bin/retry

RUN retry apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-17-jdk-headless unzip git curl file ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && retry curl -fsSL -o /tmp/cmdtools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" \
    && unzip -q /tmp/cmdtools.zip -d "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm /tmp/cmdtools.zip
ENV PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}"

RUN yes | retry sdkmanager --licenses >/dev/null \
    && retry sdkmanager --install \
        "platform-tools" \
        "platforms;${ANDROID_PLATFORM}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" \
        "ndk;${NDK_VERSION}" >/dev/null
ENV ANDROID_NDK_HOME="${ANDROID_SDK_ROOT}/ndk/${NDK_VERSION}" \
    ANDROID_NDK_ROOT="${ANDROID_SDK_ROOT}/ndk/${NDK_VERSION}"

# gomobile toolchain, pinned to gradle/libs.versions.toml `gomobile`.
# GOTOOLCHAIN=auto: fetch the exact Go a go.mod directive needs (libXray pins
# go 1.26.2). Keep the public checksum database enabled.
ARG GOMOBILE_VERSION=v0.0.0-20260529142300-ecb4cd65260a
ENV GOSUMDB=sum.golang.org GOPROXY=https://proxy.golang.org,direct GOTOOLCHAIN=auto
RUN retry go install golang.org/x/mobile/cmd/gomobile@${GOMOBILE_VERSION} \
    && retry go install golang.org/x/mobile/cmd/gobind@${GOMOBILE_VERSION}
ENV PATH="/go/bin:${PATH}"
RUN gomobile init

WORKDIR /work
ENTRYPOINT ["/bin/bash"]
