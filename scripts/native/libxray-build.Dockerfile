# Reproducible container lane for the libXray Android ABI packaging.
# Builds the exact toolchain scripts/native/build-libxray.sh needs:
#   Go 1.27.4 (certificate verifier security fix) + Android SDK + NDK + gomobile.
# Matches RIPDPI gradle.properties: compileSdk=37.2, NDK=30.0.16248370.
#
# HOST ARCHITECTURE: build and run this Linux image on x86_64. The pinned
# gomobile does not support Linux arm64 Android builds. The host producer also
# supports native arm64 macOS through the NDK's universal clang binary; see
# docs/native/libxray-packaging.md, Host architecture.
#
# Usage:
#   docker build --platform linux/amd64 -f scripts/native/libxray-build.Dockerfile -t ripdpi/libxray-build .
#   docker run --rm --platform linux/amd64 -v "$PWD":/work:ro -e RIPDPI_XRAY_AAR_DIR=/artifacts \
#     ripdpi/libxray-build -c 'cd /work && bash scripts/native/build-libxray.sh \
#       && bash scripts/native/verify-libxray-artifacts.sh'
FROM golang:1.27.4-trixie

ARG ANDROID_CMDLINE_TOOLS_VERSION=16111833
ARG ANDROID_PLATFORM=android-37.2
ARG ANDROID_BUILD_TOOLS=37.0.0
ARG NDK_VERSION=30.0.16248370

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk

# Retry wrapper so transient network blips don't fail the whole build.
RUN printf '#!/bin/sh\nn=0; until "$@"; do n=$((n+1)); [ $n -ge 5 ] && exit 1; echo "retry $n: $*"; sleep 5; done\n' > /usr/local/bin/retry \
    && chmod +x /usr/local/bin/retry

RUN retry apt-get update \
    && apt-get install -y --no-install-recommends \
        openjdk-21-jdk-headless unzip git curl file ca-certificates \
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
# Keep this version aligned with native/xray/patches/manifest.json.
# Keep the public checksum database enabled.
ARG GOMOBILE_VERSION=v0.0.0-20261011034528-197770444867
ENV GOSUMDB=sum.golang.org GOPROXY=https://proxy.golang.org,direct GOTOOLCHAIN=go1.27.4
RUN retry go install golang.org/x/mobile/cmd/gomobile@${GOMOBILE_VERSION} \
    && retry go install golang.org/x/mobile/cmd/gobind@${GOMOBILE_VERSION}
ENV PATH="/go/bin:${PATH}"
# Do not run gomobile init: it can replace the pinned gobind.

WORKDIR /work
ENTRYPOINT ["/bin/bash"]
