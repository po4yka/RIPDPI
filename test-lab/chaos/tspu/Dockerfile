# TSPU adversarial emulator -- live-mode container (skeleton, v1.1).
#
# This image is the host for the nfqueue-attached classifier that lands
# in the live-mode follow-up PR. The v1.1 container is intentionally
# minimal: it installs the userspace pieces (nftables, libnetfilter_queue,
# python3, scapy) so the follow-up PR only has to add the live handler
# under runner/live.py and the nftables rules under runner/nft/.
#
# v1.1 does NOT run the live handler. The container's default entrypoint
# is the dry-run replayer so the same image is usable for the existing
# dry-run lane in CI (Linux PR runners), and so changes to the image
# can be exercised before live mode is wired up.

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

ENV PYTHONPATH=/opt/tspu

# Default entrypoint is the dry-run replayer. Override with `live`
# once the live-mode handler lands.
ENTRYPOINT ["python3", "-m", "runner.cli"]
CMD ["dry-run", "--matrix", "matrix.json", "--fixtures", "fixtures", "--out-dir", "/var/lib/tspu-out"]
