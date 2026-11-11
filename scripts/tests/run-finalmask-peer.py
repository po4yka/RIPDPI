#!/usr/bin/env python3
"""Check the documented Finalmask subset against an owned loopback Xray peer."""

import argparse
import copy
import json
from pathlib import Path
import socket
import socketserver
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = ROOT / "docs/examples/finalmask"
PUBLIC_KEY = "_TOE4TKtAqVsePRVR-5AA43HkAK5DSntkOCO7nYq5xU"


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_ready(process, port):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("owned peer exited before readiness")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                return
        except OSError:
            time.sleep(0.02)
    raise TimeoutError("owned peer did not listen within five seconds")


def stop(process):
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


class Echo(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(20)
        try:
            while data := self.request.recv(4096):
                self.request.sendall(data)
        except (TimeoutError, ConnectionError):
            pass


class EchoServer(socketserver.ThreadingTCPServer):
    daemon_threads = True


def run(args, directory):
    version = subprocess.check_output([args.xray, "version"], text=True)
    if "26.10.21" not in version.splitlines()[0]:
        raise RuntimeError("expected Xray v26.10.21; see SPEC_VERSION.md for its exact commit")
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
         "-subj", "/CN=fixture.example", "-keyout", str(directory / "key.pem"),
         "-out", str(directory / "cert.pem")],
        check=True, capture_output=True, timeout=20,
    )
    decoy_port, relay_port = free_port(), free_port()
    with (directory / "decoy.log").open("w") as decoy_log:
        decoy = subprocess.Popen(
            ["openssl", "s_server", "-accept", f"127.0.0.1:{decoy_port}", "-tls1_3",
             "-alpn", "h2", "-www", "-cert", str(directory / "cert.pem"),
             "-key", str(directory / "key.pem")],
            stdout=decoy_log, stderr=subprocess.STDOUT,
        )
        try:
            wait_ready(decoy, decoy_port)
            with EchoServer(("127.0.0.1", 0), Echo) as echo:
                threading.Thread(target=echo.serve_forever, daemon=True).start()
                try:
                    check_variants(args, directory, relay_port, decoy_port, echo.server_address[1])
                finally:
                    echo.shutdown()
        finally:
            stop(decoy)


def check_variants(args, directory, relay_port, decoy_port, target_port):
    template = json.loads((EXAMPLES / "xhttp-finalmask-server.json").read_text())
    # This origin is behind Cloudflare's TLS termination and has no outer mask.
    subprocess.run(
        [args.xray, "run", "-test", "-c", str(EXAMPLES / "cloudflare-tunnel-finalmask-server.json")],
        check=True, capture_output=True, timeout=10,
    )
    for mask in ["off", "header_custom", "noise", "fragment", "sudoku"]:
        config = copy.deepcopy(template)
        config["log"] = {"loglevel": "debug"}
        inbound = config["inbounds"][0]
        inbound["port"] = relay_port
        settings = inbound["streamSettings"]
        settings["realitySettings"]["target"] = f"127.0.0.1:{decoy_port}"
        if mask in ["off", "fragment"]:
            settings.pop("finalmask")  # TCP reassembly needs no inverse mask.
        elif mask == "noise":
            settings["finalmask"]["tcp"][0]["settings"]["clients"] = [[{"rand": 4}]]
        elif mask == "sudoku":
            settings["finalmask"] = {"tcp": [{"type": "sudoku", "settings": {"password": " fixture-seed "}}]}
        config["outbounds"] = [
            {"tag": "owned", "protocol": "freedom", "settings": {"finalRules": [
                {"action": "allow", "network": "tcp", "ip": ["127.0.0.1"], "port": str(target_port)},
            ]}},
            {"tag": "blocked", "protocol": "blackhole"},
        ]
        config["routing"] = {"rules": [
            {"type": "field", "ip": ["127.0.0.1"], "port": str(target_port), "outboundTag": "owned"},
            {"type": "field", "network": "tcp,udp", "outboundTag": "blocked"},
        ]}
        path = directory / f"{mask}.json"
        path.write_text(json.dumps(config))
        subprocess.run([args.xray, "run", "-test", "-c", str(path)], check=True, capture_output=True, timeout=10)
        with (directory / f"{mask}.log").open("w") as log:
            peer = subprocess.Popen([args.xray, "run", "-c", str(path)], stdout=log, stderr=subprocess.STDOUT)
            try:
                wait_ready(peer, relay_port)
                for mode in ["stream-one", "stream-up"]:
                    subprocess.run(
                        [args.client, str(relay_port), str(target_port), PUBLIC_KEY, mask, mode],
                        check=True, timeout=20,
                    )
            except (subprocess.SubprocessError, OSError):
                print((directory / f"{mask}.log").read_text()[-4000:])
                raise
            finally:
                stop(peer)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--xray", required=True, help="Xray v26.10.21 binary with the documented header CloseWrite patch")
    parser.add_argument("--client", required=True, help="compiled ripdpi-xhttp finalmask_peer example")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="ripdpi-finalmask-peer-") as temp:
        run(args, Path(temp))
    print("Finalmask peer acceptance passed: five masks, two native session modes")


if __name__ == "__main__":
    main()
