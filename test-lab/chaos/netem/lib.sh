#!/usr/bin/env bash

netem_init_session() {
  : "${NETEM_STATE_DIR:?NETEM_STATE_DIR is required for transactional cleanup}"
  : "${NETEM_RUN_ID:?NETEM_RUN_ID is required for run-owned firewall rules}"
  if [[ ! "$NETEM_RUN_ID" =~ ^[A-Za-z0-9_.-]{1,64}$ ]]; then
    echo "NETEM_RUN_ID must contain 1..64 safe identifier characters" >&2
    return 2
  fi

  netem_dev="${NETEM_DEV:-eth0}"
  if [[ ! "$netem_dev" =~ ^[A-Za-z0-9_.:@-]+$ ]]; then
    echo "NETEM_DEV contains unsupported characters" >&2
    return 2
  fi
  netem_state_dir="$NETEM_STATE_DIR"
  netem_comment="ripdpi-netem-$NETEM_RUN_ID"
  netem_sudo=(sudo)
  if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
    netem_sudo=()
  fi
  mkdir -p "$netem_state_dir"

  local metadata="$netem_state_dir/session.env"
  if [[ -f "$metadata" ]]; then
    NETEM_CAPTURED_DEV="$(sed -n 's/^NETEM_CAPTURED_DEV=//p' "$metadata")"
    NETEM_CAPTURED_RUN_ID="$(sed -n 's/^NETEM_CAPTURED_RUN_ID=//p' "$metadata")"
    NETEM_CAPTURED_MTU="$(sed -n 's/^NETEM_CAPTURED_MTU=//p' "$metadata")"
    NETEM_CAPTURED_IP_FORWARD="$(sed -n 's/^NETEM_CAPTURED_IP_FORWARD=//p' "$metadata")"
    [[ "$NETEM_CAPTURED_MTU" =~ ^[0-9]+$ && "$NETEM_CAPTURED_IP_FORWARD" =~ ^[01]$ ]] || {
      echo "Captured netem session metadata is malformed" >&2
      return 2
    }
    [[ "$NETEM_CAPTURED_DEV" == "$netem_dev" && "$NETEM_CAPTURED_RUN_ID" == "$NETEM_RUN_ID" ]] || {
      echo "NETEM_STATE_DIR belongs to a different device or run" >&2
      return 2
    }
    return 0
  fi

  local mtu ip_forward
  mtu="$(ip link show dev "$netem_dev" | awk '{for (i=1;i<=NF;i++) if ($i=="mtu") {print $(i+1); exit}}')"
  ip_forward="$(sysctl -n net.ipv4.ip_forward)"
  [[ "$mtu" =~ ^[0-9]+$ && "$ip_forward" =~ ^[01]$ ]] || {
    echo "Could not capture the network baseline" >&2
    return 1
  }
  tc qdisc show dev "$netem_dev" >"$netem_state_dir/qdisc.before"
  if grep -Eq '^qdisc netem ' "$netem_state_dir/qdisc.before"; then
    rm -f "$netem_state_dir/qdisc.before"
    echo "Refusing to layer a RIPDPI session over an existing netem qdisc" >&2
    return 1
  fi
  cat >"$metadata" <<EOF
NETEM_CAPTURED_DEV=$netem_dev
NETEM_CAPTURED_RUN_ID=$NETEM_RUN_ID
NETEM_CAPTURED_MTU=$mtu
NETEM_CAPTURED_IP_FORWARD=$ip_forward
EOF
  NETEM_CAPTURED_DEV="$netem_dev"
  NETEM_CAPTURED_RUN_ID="$NETEM_RUN_ID"
  NETEM_CAPTURED_MTU="$mtu"
  NETEM_CAPTURED_IP_FORWARD="$ip_forward"
}

netem_add_rule() {
  local family="$1"
  shift
  if ! "${netem_sudo[@]}" "$family" -C "$@" -m comment --comment "$netem_comment" 2>/dev/null; then
    "${netem_sudo[@]}" "$family" -I "$@" -m comment --comment "$netem_comment"
  fi
}
