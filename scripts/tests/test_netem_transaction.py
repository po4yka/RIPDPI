#!/usr/bin/env python3

from __future__ import annotations

import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
NETEM = ROOT / "test-lab/chaos/netem"


class NetemTransactionTest(unittest.TestCase):
    def make_tools(self, root: Path) -> tuple[Path, Path]:
        bin_dir = root / "bin"
        bin_dir.mkdir()
        log = root / "commands.log"
        tools = {
            "sudo": '#!/usr/bin/env bash\nprintf "sudo %s\\n" "$*" >> "$COMMAND_LOG"\nexec "$@"\n',
            "ip": """#!/usr/bin/env bash
                printf 'ip %s\n' "$*" >> "$COMMAND_LOG"
                if [[ "$1 $2 $3" == "link show dev" ]]; then
                  echo '2: eth0: <UP> mtu 1500 qdisc fq_codel state UP'
                fi
            """,
            "sysctl": """#!/usr/bin/env bash
                printf 'sysctl %s\n' "$*" >> "$COMMAND_LOG"
                [[ "$1" == "-n" ]] && echo 0
                exit 0
            """,
            "tc": """#!/usr/bin/env bash
                printf 'tc %s\n' "$*" >> "$COMMAND_LOG"
                if [[ "$1 $2" == "qdisc show" ]]; then
                  echo 'qdisc fq_codel 0: root refcnt 2 limit 10240p'
                fi
            """,
            "iptables": """#!/usr/bin/env bash
                printf 'iptables %s\n' "$*" >> "$COMMAND_LOG"
                [[ " $* " == *' -C '* ]] && exit 1
                exit 0
            """,
            "ip6tables": """#!/usr/bin/env bash
                printf 'ip6tables %s\n' "$*" >> "$COMMAND_LOG"
                [[ " $* " == *' -C '* ]] && exit 1
                exit 0
            """,
        }
        for name, source in tools.items():
            path = bin_dir / name
            path.write_text(textwrap.dedent(source), encoding="utf-8")
            path.chmod(0o755)
        return bin_dir, log

    def environment(self, root: Path, bin_dir: Path, log: Path) -> dict[str, str]:
        env = os.environ.copy()
        env.update(
            PATH=f"{bin_dir}:{env['PATH']}",
            COMMAND_LOG=str(log),
            NETEM_DEV="eth0",
            NETEM_RUN_ID="phase16-row-1",
            NETEM_STATE_DIR=str(root / "state"),
            NETEM_NAT_SUBNET="192.0.2.0/24",
        )
        return env

    def test_restores_mtu_forwarding_qdisc_and_owned_rules(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            bin_dir, log = self.make_tools(root)
            env = self.environment(root, bin_dir, log)
            apply = subprocess.run(
                ["bash", str(NETEM / "apply-carrier-nat-reorder.sh")],
                env=env,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, apply.returncode, apply.stderr + "\n" + log.read_text(encoding="utf-8"))
            clear = subprocess.run(
                ["bash", str(NETEM / "clear.sh")],
                env=env,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, clear.returncode, clear.stderr)
            commands = log.read_text(encoding="utf-8")
            self.assertIn("--comment ripdpi-netem-phase16-row-1", commands)
            self.assertIn("sysctl -w net.ipv4.ip_forward=0", commands)
            self.assertIn("ip link set dev eth0 mtu 1500", commands)
            self.assertIn("tc qdisc del dev eth0 root", commands)
            self.assertFalse((root / "state").exists())

    def test_refuses_untracked_or_cross_run_cleanup(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            bin_dir, log = self.make_tools(root)
            env = self.environment(root, bin_dir, log)
            missing = subprocess.run(
                ["bash", str(NETEM / "clear.sh")], env=env, text=True, capture_output=True, check=False
            )
            self.assertNotEqual(0, missing.returncode)
            self.assertIn("No captured netem session", missing.stderr)

            subprocess.run(
                ["bash", str(NETEM / "apply-delay.sh")], env=env, check=True, capture_output=True, text=True
            )
            env["NETEM_RUN_ID"] = "another-run"
            wrong_owner = subprocess.run(
                ["bash", str(NETEM / "clear.sh")], env=env, text=True, capture_output=True, check=False
            )
            self.assertNotEqual(0, wrong_owner.returncode)
            self.assertIn("different device or run", wrong_owner.stderr)


if __name__ == "__main__":
    unittest.main()
