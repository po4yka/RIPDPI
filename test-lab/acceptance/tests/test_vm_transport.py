import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from contract import ContractError
from vm_transport import vm_mappings, cancel_vm


class MappingTests(unittest.TestCase):
    def inventory(self, **overrides):
        machine = {
            "name": "ripdpi-acceptance-test",
            "status": "Running",
            "config": {
                "mounts": [
                    {
                        "location": "/tmp/repo",
                        "mountPoint": "/srv/ripdpi",
                        "writable": False,
                    },
                    {
                        "location": "/tmp/evidence",
                        "mountPoint": "/srv/acceptance-output",
                        "writable": True,
                    },
                ]
            },
        }
        machine.update(overrides)
        return json.dumps(machine)

    def test_cancel_is_bounded_and_uses_guest_output(self):
        with (
            patch("vm_transport.vm_mappings", return_value=("/src", "/out/row")),
            patch("vm_transport.subprocess.run") as run,
        ):
            run.return_value.returncode = 0
            self.assertTrue(
                cancel_vm("ripdpi-acceptance-test", Path("/r"), Path("/o"), "run-1")
            )
        args, kwargs = run.call_args
        self.assertIn("/src/test-lab/acceptance/vm/cancel.py", args[0])
        self.assertEqual("/out/row", args[0][-1])
        self.assertEqual(45, kwargs["timeout"])

    def test_maps_exact_checkout_and_output(self):
        self.assertEqual(
            ("/srv/ripdpi", "/srv/acceptance-output/run/row"),
            vm_mappings(
                "ripdpi-acceptance-test",
                Path("/tmp/repo"),
                Path("/tmp/evidence/run/row"),
                self.inventory(),
            ),
        )

    def test_rejects_unrelated_vm(self):
        with self.assertRaises(ContractError):
            vm_mappings(
                "personal-vm",
                Path("/tmp/repo"),
                Path("/tmp/evidence"),
                self.inventory(),
            )

    def test_rejects_wrong_checkout(self):
        with self.assertRaises(ContractError):
            vm_mappings(
                "ripdpi-acceptance-test",
                Path("/tmp/other"),
                Path("/tmp/evidence"),
                self.inventory(),
            )

    def test_rejects_output_outside_mount(self):
        with self.assertRaises(ContractError):
            vm_mappings(
                "ripdpi-acceptance-test",
                Path("/tmp/repo"),
                Path("/tmp/unmounted"),
                self.inventory(),
            )

    def test_rejects_stopped_vm(self):
        with self.assertRaises(ContractError):
            vm_mappings(
                "ripdpi-acceptance-test",
                Path("/tmp/repo"),
                Path("/tmp/evidence"),
                self.inventory(status="Stopped"),
            )

    def test_rejects_writable_sources(self):
        data = json.loads(self.inventory())
        data["config"]["mounts"][0]["writable"] = True
        with self.assertRaises(ContractError):
            vm_mappings(
                "ripdpi-acceptance-test",
                Path("/tmp/repo"),
                Path("/tmp/evidence"),
                json.dumps(data),
            )


if __name__ == "__main__":
    unittest.main()
