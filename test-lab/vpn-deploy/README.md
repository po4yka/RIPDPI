# vpn-deploy local integration lab

Bring up the sibling `ripdpi-vpn-deploy` stack as a single privileged
Debian container with published host ports, so RIPDPI emulators can
talk to a real xray + nginx-XHTTP + hysteria server without standing
up a UpCloud VPS.

This is the published-ports counterpart of the deploy repo's
`molecule-full-stack` test. CI continues to use the unpublished
`full-stack` scenario.

## Prerequisites

- Docker Desktop (host arch is auto-detected, image is multi-arch).
- The sibling repo cloned at `${RIPDPI_VPN_DEPLOY_DIR:-~/GitHub/ripdpi-vpn-deploy}`.
- Python 3.12 on PATH (a venv is created automatically by `start.sh`).

No SOPS or real secrets needed: the synthetic `test-secrets.yaml` from
the deploy repo's `full-stack` scenario is reused by reference.

## Usage

```sh
test-lab/vpn-deploy/start.sh             # bring stack up, leave running
test-lab/vpn-deploy/start.sh --status    # show endpoint table
test-lab/vpn-deploy/stop.sh              # tear down container
```

## Endpoints (host -> container)

| Profile                       | Host                          | Container       |
| ----------------------------- | ----------------------------- | --------------- |
| sshd (debug)                  | `127.0.0.1:12222/tcp`         | `:22/tcp`       |
| xray VLESS+REALITY+Vision (P0)| `127.0.0.1:31443/tcp`         | `:443/tcp`      |
| hysteria2 (P2)                | `127.0.0.1:31443/udp`         | `:443/udp`      |
| nginx XHTTP front (P1)        | `127.0.0.1:31844/tcp`         | `:8443/tcp`     |

Pointing an Android emulator (`RIPDPI_NonRooted_API35` or
`RIPDPI_Rooted_API35`) at these endpoints requires Android's
emulator host alias `10.0.2.2` (not `127.0.0.1`). ReDroid inside
the Lima VM uses `host.docker.internal` instead.

## Caveats

- amneziawg, warp-outbound, backup are intentionally disabled —
  none of them survive a Docker test cell.
- The test-secrets.yaml ships REALITY keys and Hysteria auth that are
  synthetic. Treat the published container as a security-degraded
  reference target, never as a production server.
- Tear down with `stop.sh` before sleeping the laptop; running
  systemd-in-docker on Apple Silicon hot-spins CPU under load.
