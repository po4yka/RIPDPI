"""Mock-only checks: these tests never change a device or execute sudo."""
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from scripts.ci import capture_kvm_access as kvm


def device_stat(uid=0, mode=0o660, inode=42):
    return SimpleNamespace(st_uid=uid, st_gid=108, st_mode=stat.S_IFCHR | mode,
                           st_ino=inode, st_dev=5, st_rdev=123)


class CaptureKvmAccessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name) / 'state.json'
        self.device = Mock()
        self.device.__str__ = Mock(return_value='/dev/kvm')
        self.device.lstat.return_value = device_stat()
        self.addCleanup(patch.stopall)
        patch.object(kvm, 'DEVICE', self.device).start()
        self.run = patch.object(kvm, 'run').start()
        patch.dict(os.environ, {'CI': 'true', 'GITHUB_ACTIONS': 'true'}).start()
        patch.object(kvm.os, 'getuid', return_value=1001).start()
        patch.object(kvm.os, 'access', return_value=True).start()

    def test_only_capture_owner_gains_access_and_original_state_is_saved(self):
        kvm.enable(self.state)
        self.assertEqual(self.run.call_args_list[0].args, ('chown', '1001:108', '/dev/kvm'))
        self.assertEqual(self.run.call_args_list[1].args, ('chmod', '0660', '/dev/kvm'))
        self.assertEqual(json.loads(self.state.read_text())['uid'], 0)

    def test_receipt_is_written_before_first_mutation_even_if_command_fails(self):
        def fail(*_args):
            self.assertTrue(self.state.exists())
            raise subprocess.CalledProcessError(1, 'sudo')
        self.run.side_effect = fail
        with self.assertRaises(subprocess.CalledProcessError):
            kvm.enable(self.state)
        self.run.side_effect = None
        kvm.restore(self.state)
        self.assertEqual(self.run.call_args_list[-2].args, ('chown', '0:108', '/dev/kvm'))

    def test_partial_chown_success_and_chmod_failure_remains_restorable(self):
        self.run.side_effect = [None, subprocess.CalledProcessError(1, 'sudo chmod')]
        with self.assertRaises(subprocess.CalledProcessError):
            kvm.enable(self.state)
        self.assertTrue(self.state.exists())
        self.run.side_effect = None
        self.device.lstat.side_effect = [device_stat(uid=1001), device_stat()]
        kvm.restore(self.state)
        self.assertEqual(self.run.call_args_list[-2].args, ('chown', '0:108', '/dev/kvm'))
        self.assertEqual(self.run.call_args_list[-1].args, ('chmod', '0660', '/dev/kvm'))

    def test_restore_checks_original_owner_and_mode(self):
        kvm.enable(self.state)
        self.run.reset_mock()
        self.device.lstat.side_effect = [device_stat(uid=1001), device_stat()]
        kvm.restore(self.state)
        self.assertEqual(self.run.call_args_list[0].args, ('chown', '0:108', '/dev/kvm'))
        self.assertEqual(self.run.call_args_list[1].args, ('chmod', '0660', '/dev/kvm'))

    def test_absent_device_fails_without_mutation(self):
        self.device.lstat.side_effect = FileNotFoundError('/dev/kvm')
        with self.assertRaises(FileNotFoundError):
            kvm.enable(self.state)
        self.run.assert_not_called()
        self.assertFalse(self.state.exists())

    def test_rejects_non_ci_and_preserves_existing_receipt(self):
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'false'}):
            with self.assertRaisesRegex(RuntimeError, 'disposable'):
                kvm.enable(self.state)
        self.state.write_text('preserve')
        with self.assertRaisesRegex(RuntimeError, 'overwritten'):
            kvm.enable(self.state)
        self.run.assert_not_called()
        self.assertEqual(self.state.read_text(), 'preserve')

    def test_rejects_non_character_device_and_replaced_device(self):
        self.device.lstat.return_value = SimpleNamespace(st_mode=stat.S_IFREG | 0o644)
        with self.assertRaisesRegex(RuntimeError, 'character device'):
            kvm.enable(self.state)
        self.device.lstat.return_value = device_stat()
        kvm.enable(self.state)
        self.run.reset_mock()
        self.device.lstat.return_value = device_stat(inode=99)
        with self.assertRaisesRegex(RuntimeError, 'changed identity'):
            kvm.restore(self.state)
        self.run.assert_not_called()

    def test_restore_without_receipt_does_nothing(self):
        kvm.restore(self.state)
        self.run.assert_not_called()
        self.device.lstat.assert_not_called()

    def test_failed_restoration_is_not_reported_successful(self):
        kvm.enable(self.state)
        self.device.lstat.return_value = device_stat(uid=1001)
        with self.assertRaisesRegex(RuntimeError, 'did not take effect'):
            kvm.restore(self.state)


if __name__ == '__main__':
    unittest.main()
