# Linux acceptance VM

This lane uses real Linux routes, network namespaces, TCP and UDP sockets,
`nftables`, and `tc/netem`. It does not emulate a VPN server. The protocol peers
and Android adapter supply the protocol and application assertions.

## Prepare a disposable VM on an ARM64 Mac

Use a job worktree. The helper refuses `main`, an existing VM, overlapping mounts,
and instance names outside the `ripdpi-acceptance-` prefix. The workspace is
read-only in Linux. Keep output outside that workspace.

```bash
python3 test-lab/acceptance/vm/lab_vm.py create \
  --name ripdpi-acceptance-local --workspace "$PWD" \
  --out-dir /tmp/ripdpi-acceptance-local
python3 test-lab/acceptance/vm/lab_vm.py start \
  --name ripdpi-acceptance-local --out-dir /tmp/ripdpi-acceptance-local
python3 test-lab/acceptance/vm/lab_vm.py doctor \
  --name ripdpi-acceptance-local --out-dir /tmp/ripdpi-acceptance-local
```

The Ubuntu image has a dated URL and SHA-256 digest. Ubuntu packages use the
`20260926T000000Z` archive snapshot. Installed versions are recorded in
`/var/lib/ripdpi-acceptance/packages.tsv`. The host must download these inputs
before offline acceptance. Docker images and peer binaries have their own pins.

The VM has 4 CPUs, 8 GiB RAM, Docker Engine, and a `vzNAT` interface named
`acceptance0`. `doctor` reports its IPv4 address. Android Emulator stays on macOS.
Use this VM address for data and control endpoints; `10.0.2.2` is the Mac loopback.
Lima automatic TCP and UDP forwarding is disabled so it cannot bypass routing.

## Routed fault proof

Pass the exact source commit explicitly in the VM: the mounted worktree's `.git`
file refers to a host path which is not mounted. Keep dirty-source results separate
from committed acceptance evidence. Use a fresh output directory for each run.

```bash
source_sha="$(git rev-parse HEAD)"
limactl shell --workdir=/srv/ripdpi ripdpi-acceptance-local sudo python3 \
  test-lab/acceptance/vm/run.py --scenario routed-baseline-drop-recovery \
  --run-id local-drop --out-dir /srv/acceptance-output/local-drop \
  --source-sha "$source_sha"
```

The adapter emits `result.json`, server receipts, rule counters, qdisc statistics,
phase results, and cleanup evidence. A missing capability is `blocked`, never a
pass. Required assertions include real IPv4/IPv6 TCP/UDP baseline and recovery,
control traffic during the fault, and no peer route to external destinations.
Only the fixture's own test payloads enter these artifacts.

| Scenario | Observable assertion |
|---|---|
| `routed-baseline-drop-recovery` | Both TCP and UDP stop; fault counter increases; both recover |
| `routed-udp-block` | UDP stops while TCP passes |
| `routed-tcp-app-blackhole` | An established TCP application connection stops after the fault is armed; UDP passes |
| `routed-delay` | Measured echo delay increases with netem on both directions |
| `routed-loss` | A UDP sample receives some, but not all, replies |
| `routed-reorder` | A UDP burst returns an out-of-order sequence |
| `routed-mtu-blackhole` | 1400-byte UDP payload stops; a small payload passes; oversized-packet counter increases |
| `routed-ipv6-block` | IPv6 stops while IPv4 remains usable |
| `packet-fidelity` | The same native UDP payload appears on both router sides; source address is unchanged and TTL decreases by one |
| `packet-engine` | Actual RIPDPI CLI strategies pass all required registry oracles with raw captures and eight generated cases |

Loss and reorder use the Linux random model. They are bounded statistical checks,
not a promise of byte-identical traces. `tcp-app-blackhole` is armed after a known
application handshake; it does not classify encrypted TLS records. The MTU case
models a packet-size blackhole, not every PMTU discovery implementation.

The `packet-fidelity` case proves the Linux topology. The `packet-engine` case
runs the repository's existing engine packet tests. Neither derives original
Android packets from emulator NAT. Raw PCAPs are listed as `private_artifacts` and
excluded from the adapter's public artifact list. Do not upload raw captures by
default.

Prepare the optional engine lane inside the task-owned VM:

```bash
limactl shell --workdir=/srv/ripdpi ripdpi-acceptance-local \
  sudo bash test-lab/acceptance/vm/prepare-engine.sh
```

Preparation installs build tools from the same snapshot, selects the repository
Rust toolchain, fetches locked dependencies, and builds the packet test. Runtime
sets `CARGO_NET_OFFLINE=true`, uses raw captures, rejects skips/zero tests, and
checks every mandatory case artifact. The engine process group must be gone before
cleanup passes. A normal run with orphan processes fails its required assertion.

## Attach real protocol peers

`router.py up --run-id ID --out-dir DIR` creates three namespaces and writes
`DIR/topology.json`. All names and addresses come from this file:

```text
native client -- router -- peer data interface
                    |
             VM ingress veth

VM control veth ------------ peer management interface
```

The peer has routes to the client and ingress subnets, plus its directly connected
management subnet. It has no default IPv4 or IPv6 route. The router has no default
route and a default-deny forwarding chain. There is no public data egress.
Preparation downloads use the VM root namespace before the peer starts.

Run a binary with `sudo ip netns exec PEER_NAMESPACE BINARY ...`. For Xray, bind
data to `peer_ipv4`, control to `peer_management_ipv4`, and advertise the VM
`ingress_ipv4`. Get the assigned ports from the peer's ready file and manifest.
Publish each data port with its number unchanged:

```bash
sudo python3 test-lab/acceptance/vm/router.py publish \
  --run-id ID --out-dir DIR --ingress-ip VM_IP \
  --ports TCP_PORT,XHTTP_PORT,DIRECT_PORT,DNS_HTTP_PORT \
  --control-ports CONTROL_PORT
```

For Docker, first start a run-owned `--network=none` anchor, then supply its Linux
PID to `router.py up --peer-pid PID`. Additional containers can use
`--network=container:ANCHOR`. The adapter rejects the root network namespace and
an adopted namespace that has routes. It never stops the container itself.

Publication uses per-run DNAT/SNAT tables and a restricted iptables chain. It
preserves Docker's global forwarding policy. Only one run can publish ports at a
time; other namespace-only runs can coexist. Root forwarding state is saved and
restored. Data and management ports must be different. Management uses the direct
veth and remains available during faults. Stop peer processes before `down`.

```bash
sudo python3 test-lab/acceptance/vm/router.py apply \
  --run-id ID --out-dir DIR --profile udp-block
sudo python3 test-lab/acceptance/vm/router.py status --run-id ID --out-dir DIR
sudo python3 test-lab/acceptance/vm/router.py apply \
  --run-id ID --out-dir DIR --profile baseline
sudo python3 test-lab/acceptance/vm/router.py down --run-id ID --out-dir DIR
```

Cleanup never flushes a host ruleset and refuses namespaces with live unmanaged
processes. Its root-owned registry is under `/run/ripdpi-acceptance`; an output
file alone cannot authorize deletion of resources. Interrupted cleanup is
resumable. Address collisions fail before setup; choose a new run ID.

To cancel a VM adapter, use the root-owned registration. The helper validates the
PID, Linux start ticks, command digest, run ID, and output path before it sends
SIGTERM. The runner records a failed result, stops its children, and removes its
network resources. The command waits for that cleanup and fails if it is absent:

```bash
sudo python3 test-lab/acceptance/vm/cancel.py --run-id ID --out-dir DIR
```

Do not assume that terminating the Mac `limactl` client cancels the remote job.

Stop the owned VM when the session ends. The helper never deletes a VM, disk,
worktree, or branch:

```bash
python3 test-lab/acceptance/vm/lab_vm.py stop \
  --name ripdpi-acceptance-local --out-dir /tmp/ripdpi-acceptance-local
```

Run local regression tests with:

```bash
python3 -m unittest discover -s test-lab/acceptance/vm/tests -v
```
