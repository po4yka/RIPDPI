# TSPU adversarial emulator -- v1.1 container.
#
# Hosts both the dry-run replayer (default entrypoint, no kernel
# requirements) and the live-mode nfqueue-attached classifier. Live mode
# requires the container to be invoked with cap_add: NET_ADMIN and
# uses the entrypoint-live.sh wrapper to load nft rules before starting
# the Python handler.

FROM debian:bookworm-slim

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install --no-install-recommends -y \
        nftables \
        iproute2 \
        iptables \
        libnetfilter-queue1 \
        python3 \
        python3-netfilterqueue \
        python3-scapy \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/tspu

COPY README.md ./README.md
COPY matrix.json ./matrix.json
COPY patterns ./patterns
COPY runner ./runner
COPY fixtures ./fixtures

RUN chmod +x /opt/tspu/runner/entrypoint-live.sh

ENV PYTHONPATH=/opt/tspu

# Default entrypoint is the dry-run replayer (no kernel deps). For live
# mode, override entrypoint to runner/entrypoint-live.sh and run the
# container with cap_add: NET_ADMIN (see docker-compose.tspu.yml).
ENTRYPOINT ["python3", "-m", "runner.cli"]
CMD ["dry-run", "--matrix", "matrix.json", "--fixtures", "fixtures", "--out-dir", "/var/lib/tspu-out"]
