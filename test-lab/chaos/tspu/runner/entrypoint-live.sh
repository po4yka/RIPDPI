#!/usr/bin/env sh
# Live-mode entrypoint wrapper.
#
# 1. Loads the nftables ruleset from runner/nft/.
# 2. Hands off to the Python live handler.
#
# Requires the container to run with cap_add: NET_ADMIN (otherwise nft
# fails before the Python handler starts).

set -eu

NFT_FILE="/opt/tspu/runner/nft/tspu-outbound.nft"
MATRIX_FILE="${MATRIX_FILE:-/opt/tspu/matrix.json}"
OUT_DIR="${OUT_DIR:-/var/lib/tspu-out}"
QUEUE_NUM="${QUEUE_NUM:-0}"

if [ ! -f "$NFT_FILE" ]; then
    echo "missing nft ruleset at $NFT_FILE" >&2
    exit 2
fi

mkdir -p "$OUT_DIR"

echo "tspu live: loading nft rules from $NFT_FILE"
nft -f "$NFT_FILE"

echo "tspu live: starting python handler (queue=$QUEUE_NUM, out=$OUT_DIR)"
exec python3 -m runner.cli live \
    --matrix "$MATRIX_FILE" \
    --out-dir "$OUT_DIR" \
    --queue-num "$QUEUE_NUM"
