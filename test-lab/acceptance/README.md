# Local VPN acceptance

This system runs local protocol and Android acceptance without rented VPN servers.
It reuses the repository's independent peers, real Android TUN tests, and packet
smoke tests. It does not replace external-provider or physical-device release gates.

## Evidence levels

| Tier | What a pass proves |
| --- | --- |
| `native-contract` | The exact native test passed against the named repository fixture. |
| `independent-peer` | The exact native client test passed against a pinned independent implementation. |
| `android-tun` | Separate-UID traffic crossed the real Android VPN path; required request, failure, and recovery assertions passed. |
| `routed-fault` | Real TCP/UDP traffic crossed a Linux router; required baseline, fault, recovery, and cleanup controls passed. |
| `packet-fidelity` | Captures came from a real Linux path. `packet-fidelity` checks the topology; `packet-engine` runs the RIPDPI packet strategy registry. |

These levels are not interchangeable. Import markers, UI presets, mock-relay JSON,
and the debug probe's derived `vpnActive` flags do not prove network acceptance.
Hysteria payload/auth tests use explicit insecure local TLS; a separate case
requires rejection of an untrusted issuer. This is not trusted-CA acceptance.

`manifest.json` is generated from the adapter catalogs. Do not edit it manually:

```bash
python3 test-lab/acceptance/generate_manifest.py
python3 test-lab/acceptance/generate_manifest.py --check
python3 test-lab/acceptance/lab.py validate
python3 test-lab/acceptance/lab.py list --profile core
```

Profiles are `native`, `android`, `routed`, `packet`, `core`, and `full`. `full`
means all cataloged local cases, not all deployment or external-provider evidence.
The report retains the external boundaries even when every local case passes.

## Prepare before a run

Use a dedicated checkout. Keep source files unchanged during each run. Build
and download dependencies before testing. Every output directory must be new;
inside the checkout, use a Git-ignored path such as `build/acceptance/`.

Native peers:

```bash
python3 test-lab/acceptance/lab.py prepare --profile native \
  --out-dir build/acceptance/prepare-native
python3 test-lab/acceptance/lab.py run --profile native \
  --out-dir build/acceptance/native-run
```

Preparation is allowed to download pinned dependencies. Native runs disable
Cargo/Go dependency resolution, but do not claim an OS-level egress firewall.
Use the routed VM for observed network isolation. On a Mac with `build-gate`,
use the machine-wide gate around compiler-backed preparation. The existing
outbound oracle's stricter Mac build-gate requirement remains in force.

Android needs real libXray and native artifacts. Follow
[libXray packaging](../../docs/native/libxray-packaging.md) and
[build performance](../../docs/contributor/build-performance.md). Never use
`ripdpi.skipNativeBuild=true` as acceptance evidence. The CI workflow builds the
managed Xray runtime and source-pinned pluggable transports before APK preparation.

Select a disposable, already booted ARM64 or x86_64 emulator explicitly. The
adapter installs the APK and changes test state on that emulator. It never resets
an unrelated AVD, selects the first attached device, or claims physical coverage.

```bash
python3 test-lab/acceptance/lab.py prepare --profile android \
  --serial emulator-5584 --out-dir build/acceptance/prepare-android
python3 test-lab/acceptance/lab.py run --profile android \
  --serial emulator-5584 \
  --prepared-dir build/acceptance/prepare-android/android \
  --out-dir build/acceptance/android-run
```

Pass `--xray-artifact-dir /absolute/verified-artifacts` during preparation when the
verified AAR lives outside the default directory. Prepared APK reuse requires
matching source, working changes, ABI, and file hashes. Go/Xray and the Rust local
network fixture are owned child processes. The default emulator path is
`10.0.2.2`; the adapter also accepts explicit private fixture endpoints.

## Linux VM on a Mac

Run the Android Emulator directly on macOS. A separate Lima ARM64 VM owns Linux
routing and peers. The VM source mount is read-only and points to this exact job
checkout. Its evidence mount is separate and writable. Existing VMs are not reused
or changed implicitly.

```bash
python3 test-lab/acceptance/vm/lab_vm.py create \
  --name ripdpi-acceptance-local \
  --workspace "$PWD" --out-dir /tmp/ripdpi-acceptance-output
python3 test-lab/acceptance/vm/lab_vm.py start \
  --name ripdpi-acceptance-local --out-dir /tmp/ripdpi-acceptance-output
python3 test-lab/acceptance/vm/lab_vm.py doctor \
  --name ripdpi-acceptance-local --out-dir /tmp/ripdpi-acceptance-output
python3 test-lab/acceptance/lab.py run --profile routed \
  --vm ripdpi-acceptance-local \
  --out-dir /tmp/ripdpi-acceptance-output/routed-run
```

Use the VM's reachable private IP from the doctor output. It is not `10.0.2.2`.
To place the real Xray Android path behind the VM router:

```bash
python3 test-lab/acceptance/lab.py run --scenario android-xray \
  --serial emulator-5584 \
  --prepared-dir build/acceptance/prepare-android/android \
  --vm ripdpi-acceptance-local --vm-address 192.168.64.4 \
  --out-dir /tmp/ripdpi-acceptance-output/xray-run
```

Replace the example address with the observed VM IP. The controller cross-builds
the pinned Go fixture for Linux ARM64, starts it in a peer namespace, publishes
its generated ports through the router, and uses a separate management link.
It collects forwarding counters and terminates only the run-owned peer before
removing its routes and firewall rules. The peer has no default public route.
A reachable direct sentinel detects an unintended unprotected fallback.

The generic routed cases prove fault placement separately from protocol behavior.
A passing routed echo case is not reported as an Android impairment test. The
Xray Android case tests server loss and recovery through its management controls.

Faults include full drop, UDP block, established TCP application blackhole, delay,
loss, reordering, MTU blackhole, and IPv6 block. The blackhole is armed after an
application baseline; it does not claim to classify encrypted TLS messages.
Run-owned namespaces and rules are removed after success and failure. Other host
routes, namespaces, and Docker policies are not flushed.

For Linux engine packet tests, use `vm/prepare-engine.sh` inside the task VM first,
then select the `packet` profile. This executes the existing packet-smoke registry
with raw captures. The VM source mount remains read-only; build outputs and tool
caches use VM-local directories. Preparation and runtime are separate commands.

```bash
limactl shell --workdir=/srv/ripdpi ripdpi-acceptance-local \
  sudo bash test-lab/acceptance/vm/prepare-engine.sh
python3 test-lab/acceptance/lab.py run --profile packet \
  --vm ripdpi-acceptance-local \
  --out-dir /tmp/ripdpi-acceptance-output/packet-run
```

Stop only the VM created for this job when finished. VM deletion is a separate
operator action; the runner never deletes VMs or worktrees.

## Reports and sign-off

Every adapter writes `result.json` with the scenario, run ID, exact source SHA,
evidence tier, mandatory checks, peer identity, artifact paths, and cleanup result.
The coordinator rejects zero-test execution, skips, missing checks, stale identities,
missing artifacts, path traversal, symlinks, failed cleanup, and false success after
an adapter error. Timeouts and unavailable prerequisites do not become passes.

The suite writes `report.json`, `report.xml`, and the selected manifest. It records
source dirtiness and a working-tree digest and rejects source changes during a run.
For release evidence, use a clean exact commit. Recheck archived evidence with:

```bash
python3 test-lab/acceptance/lab.py verify \
  --out-dir /tmp/ripdpi-acceptance-output/routed-run
```

Verification rechecks selected rows, adapter results, and artifact hashes. A subset
report certifies only its selected rows. It cannot close an external-provider gate.

Treat the whole run directory as private. Raw PCAP, synthetic credential manifests,
peer binaries, build output, and detailed device logs stay local. The CI workflow
uploads only the metadata report and JUnit summary; it does not upload the whole
run directory. Existing evidence retention/export policy still applies.

## CI and regression tests

[Local VPN acceptance](../../.github/workflows/local-acceptance.yml) runs contract
and real routed tests for changes to this system. Scheduled/manual lanes prepare
and run native peers and Android TUN cases using the same manifest. Existing
release, provider, architecture, and physical-device gates remain unchanged.

```bash
python3 -m unittest discover -s test-lab/acceptance/tests -v
python3 -m unittest discover -s test-lab/acceptance/vm/tests -v
python3 -m unittest discover -s test-lab/acceptance/peers -p 'test_*.py' -v
python3 -m unittest scripts.tests.test_android_local_acceptance -v
```

## Limits

Cloudflare registration/edge, Google Apps Script, public Tor/Snowflake service,
carrier DPI, OEM power policy, and physical Wi-Fi/cellular handover need separate
evidence. Host or emulator NAT captures cannot prove the original Android packet
shape. Native repository fixtures are labeled as such even when the protocol also
has an independent server elsewhere. No mock or unavailable scenario is promoted
to a successful production acceptance result.

## Host disk capacity

Dependency preparation checks for at least 10 GiB free on checkout and cache
volumes. This is a lower bound, not a capacity guarantee. Reserve at least 30 GiB
for an initial Android/Linux setup and run large builds in sequence. A sparse VM
disk can report free guest space while its host volume is full. Check both.
`doctor` reports host free space; `prepare --min-free-gib N` sets a stricter
threshold when needed. A disk or build failure is blocked evidence, never a pass.
