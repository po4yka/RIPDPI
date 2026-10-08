"""Run the independent Xray fixture behind the task-owned Linux router."""

from __future__ import annotations

import hashlib
import ipaddress
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

from contract import require
from vm_transport import vm_mappings


def private_ipv4(value: str) -> str:
    address = ipaddress.ip_address(value)
    require(
        address.version == 4
        and any(
            address in ipaddress.ip_network(cidr)
            for cidr in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
        )
        and not address.is_loopback
        and not address.is_unspecified,
        "--vm-address must be a reachable private VM IPv4 address",
    )
    return str(address)


def execute_routed(
    command: list[str], repo: Path, log: Path, timeout: int, options: dict, executor
) -> tuple[int, bool]:
    from execution import stop_process

    vm = options["vm"]
    address = private_ipv4(options.get("vm_address") or "")
    target = Path(command[command.index("--out-dir") + 1])
    run_id = command[command.index("--run-id") + 1]
    support = target.with_name(target.name + ".vm")
    support.mkdir(mode=0o700)
    source, guest_support = vm_mappings(vm, repo, support)
    remote = ["limactl", "shell", "--workdir=" + source, vm, "sudo", "-n"]
    router = remote + ["python3", source + "/test-lab/acceptance/vm/router.py"]
    route_output = guest_support + "/router"
    route_args = ["--run-id", run_id, "--out-dir", route_output]
    child = None
    topology = None
    cleanup = False
    result = (1, False)
    status = None
    try:
        # Go cross-compiles this pure-Go peer on the Mac; the Android AAR is separate.
        binary = support / "xray-linux-peer"
        env = dict(
            os.environ, GOOS="linux", GOARCH="arm64", CGO_ENABLED="0", GOMAXPROCS="2"
        )
        with (support / "build.private.log").open("wb") as output:
            subprocess.run(
                ["go", "build", "-mod=readonly", "-p=2", "-o", str(binary), "."],
                cwd=repo / "scripts/fixtures/xray-provider-peer",
                env=env,
                stdout=output,
                stderr=subprocess.STDOUT,
                check=True,
                timeout=1200,
            )
        subprocess.run(
            router + ["up"] + route_args, check=True, capture_output=True, timeout=30
        )
        topology = json.loads((support / "router/topology.json").read_text())
        peer_command = remote + [
            "ip",
            "netns",
            "exec",
            topology["peer"],
            guest_support + "/xray-linux-peer",
            "--bind-host",
            topology["peer_ipv4"],
            "--advertise-host",
            address,
            "--control-host",
            topology["peer_management_ipv4"],
            "--run-id",
            run_id,
            "--ready-file",
            guest_support + "/peer-ready.json",
        ]
        with (support / "peer.private.log").open("wb") as output:
            child = subprocess.Popen(
                peer_command,
                stdout=output,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        deadline = time.monotonic() + 60
        ready = support / "peer-ready.json"
        while not ready.exists():
            require(child.poll() is None, "VM peer exited before readiness")
            require(time.monotonic() < deadline, "VM peer readiness timeout")
            time.sleep(0.1)
        # A ready file can appear before the encoder has finished the first write.
        while True:
            try:
                control_port = json.loads(ready.read_text())["controlPort"]
                break
            except json.JSONDecodeError:
                require(time.monotonic() < deadline, "VM peer manifest timeout")
                time.sleep(0.05)
        control_url = (
            f"http://{topology['peer_management_ipv4']}:{control_port}/manifest"
        )
        fetch = "import sys,urllib.request; print(urllib.request.urlopen(sys.argv[1],timeout=5).read().decode())"
        manifest = json.loads(
            subprocess.check_output(
                remote + ["python3", "-c", fetch, control_url], text=True, timeout=15
            )
        )
        ports = [
            manifest[key]
            for key in ("tcpPort", "xhttpPort", "directPort", "dnsPort", "dnsHttpPort")
        ]
        require(
            all(type(port) is int and 1024 <= port <= 65535 for port in ports),
            "invalid peer ports",
        )
        subprocess.run(
            router
            + ["publish"]
            + route_args
            + [
                "--ingress-ip",
                address,
                "--ports",
                ",".join(map(str, ports)),
                "--control-ports",
                str(control_port),
            ],
            check=True,
            capture_output=True,
            timeout=30,
        )
        # Check the published management path before instrumentation. The data plane
        # remains mandatory in the Android separate-UID assertions.
        with urllib.request.urlopen(
            f"http://{address}:{control_port}/manifest", timeout=5
        ) as response:
            require(
                json.load(response).get("runId") == run_id,
                "published peer belongs to another run",
            )
        command += [
            "--xray-host",
            address,
            "--xray-control-host",
            address,
            "--host-control-host",
            address,
            "--xray-control-port",
            str(control_port),
        ]
        result = executor(command, repo, log, timeout)
        status = json.loads(
            subprocess.check_output(
                router + ["status"] + route_args, text=True, timeout=20
            )
        )
    finally:
        # Only processes in the namespace created by this run can be terminated.
        if topology:
            pids = subprocess.check_output(
                remote + ["ip", "netns", "pids", topology["peer"]],
                text=True,
                timeout=10,
            ).split()
            if pids:
                require(all(pid.isdecimal() for pid in pids), "invalid owned peer PID")
                subprocess.run(
                    remote + ["kill", "-TERM", *pids],
                    check=True,
                    capture_output=True,
                    timeout=10,
                )
            if child:
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    stop_process(child)
            subprocess.run(
                router + ["down"] + route_args,
                check=True,
                capture_output=True,
                timeout=30,
            )
            cleanup = (
                json.loads((support / "router/cleanup.json").read_text()).get("passed")
                is True
            )
        elif child:
            stop_process(child)
        if (target / "result.json").exists():
            evidence = json.loads((target / "result.json").read_text())
            evidence["cleanup"]["passed"] = (
                evidence["cleanup"].get("passed") is True and cleanup
            )
            if status:
                counters = [
                    item["counter"]
                    for item in status["firewall"]["nftables"]
                    if "counter" in item
                ]
                forwarded = any(
                    item.get("name") == "forwarded" and item.get("packets", 0) > 0
                    for item in counters
                )
                evidence["checks"].append(
                    {"id": "routed-peer-traffic", "passed": forwarded}
                )
                (target / "routed-state.json").write_text(
                    json.dumps(status, indent=2) + "\n"
                )
                evidence["artifacts"].append("routed-state.json")
                evidence["peer_identity"]["binary_sha256"] = hashlib.sha256(
                    binary.read_bytes()
                ).hexdigest()
            else:
                evidence["checks"].append(
                    {"id": "routed-peer-traffic", "passed": False}
                )
            if not cleanup or not status:
                evidence["status"] = "failed"
            (target / "result.json").write_text(json.dumps(evidence, indent=2) + "\n")
    return result
