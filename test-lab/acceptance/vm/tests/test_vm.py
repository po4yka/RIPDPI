import contextlib
import io
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import lab_vm
import pcap
import router
import packet_engine
import run as vm_run
import cancel

VM = Path(__file__).resolve().parents[1]


class InputTests(unittest.TestCase):
    def test_names_are_run_owned_and_interface_names_fit_linux(self):
        a = router.identity('request-a')
        b = router.identity('request-b')
        self.assertNotEqual(a['stem'], b['stem'])
        for key in ('router', 'peer', 'client', 'host_link', 'management_link'):
            self.assertLessEqual(len(a[key]), 15)
        for invalid in ('../foreign', '', 'a\nflush ruleset', 'x'*65):
            with self.assertRaises(ValueError):
                router.identity(invalid)

    def test_ports_reject_duplicate_privileged_and_invalid_values(self):
        self.assertEqual(router.ports('10443,10808'), [10443, 10808])
        for value in ('', '22', '10000,10000', '65536', '-1', 'abc'):
            with self.assertRaises(ValueError):
                router.ports(value)

    def test_faults_never_flush_host_firewall_and_default_deny_egress(self):
        for profile in router.PROFILES:
            rules = router.fault_rules(profile)
            self.assertIn('policy drop', rules)
            self.assertNotIn('flush ruleset', rules)
            self.assertNotIn('management', rules)
        self.assertIn('meta l4proto udp', router.fault_rules('udp-block'))
        self.assertIn('meta nfproto ipv6', router.fault_rules('ipv6-block'))
        self.assertIn('meta length > 1280', router.fault_rules('mtu-blackhole'))
        with self.assertRaises(ValueError):
            router.fault_rules('bad')

    def test_mounts_are_explicit_read_only_workspace_and_separate_output(self):
        rendered = lab_vm.render(lab_vm.MARKER, Path('/job'), Path('/artifacts'))
        mounts = json.loads(rendered.removeprefix('mounts: '))
        self.assertFalse(mounts[0]['writable'])
        self.assertTrue(mounts[1]['writable'])
        self.assertEqual(mounts[0]['mountPoint'], '/srv/ripdpi')
        for output in (Path('/job'), Path('/job/out'), Path('/')):
            with self.assertRaises(ValueError):
                lab_vm.render(lab_vm.MARKER, Path('/job'), output)

    def test_vm_cannot_adopt_user_instance_name(self):
        for name in ('ripdpi-netem', 'default', '../ripdpi-acceptance-x'):
            with self.assertRaises(ValueError):
                lab_vm.validate_name(name)
        self.assertEqual(lab_vm.validate_name('ripdpi-acceptance-test'), 'ripdpi-acceptance-test')


class OwnershipTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.registry = patch.object(router, 'REGISTRY', self.root/'registry')
        self.registry.start()
        self.addCleanup(self.registry.stop)
        router.REGISTRY.mkdir()
        self.lab = router.Router('test', self.root/'out')
        self.lab.data.update(output=str(self.lab.output), namespaces=[], links=[], published=False)
        self.lab.save()

    def test_reused_id_does_not_delete_existing_topology(self):
        with patch.object(router, 'run') as commands:
            with self.assertRaises(ValueError):
                self.lab.up()
            commands.assert_not_called()
        self.assertTrue(self.lab.path.exists())

    def test_different_output_cannot_clean_up_run(self):
        other = router.Router('test', self.root/'other')
        with patch.object(router, 'run') as commands:
            with self.assertRaises(ValueError):
                other.down()
            commands.assert_not_called()

    def test_live_peer_process_prevents_cleanup(self):
        self.lab.data.update(namespaces=['peer'], peer_adopted=False)
        self.lab.save()
        with patch.object(router, 'run', return_value='123\n') as commands:
            with self.assertRaises(RuntimeError):
                self.lab.down()
        self.assertEqual(commands.call_args.args[:3], ('ip', 'netns', 'pids'))
        self.assertTrue(self.lab.path.exists())

    def test_foreign_publication_lock_prevents_firewall_mutation(self):
        self.lab.data['published'] = True
        (router.REGISTRY/'publish-owner.json').write_text('{"run_id":"someone-else"}')
        with patch.object(router, 'run') as commands:
            with self.assertRaises(ValueError):
                self.lab.unpublish()
            commands.assert_not_called()

    def test_partial_setup_failure_rolls_back_only_created_resources(self):
        self.lab.path.unlink()
        calls = []
        def commands(*args, **kwargs):
            calls.append(args)
            if args[:4] == ('ip', 'netns', 'add', self.lab.data['peer']):
                raise RuntimeError('injected namespace allocation failure')
            if args[:4] == ('ip', '-j', 'route', 'show'):
                return '[]'
            return ''
        with patch.object(router, 'run', side_effect=commands):
            with self.assertRaisesRegex(RuntimeError, 'injected'):
                self.lab.up()
        self.assertIn(('ip', 'netns', 'del', self.lab.data['router']), calls)
        self.assertNotIn(('ip', 'netns', 'del', self.lab.data['peer']), calls)
        self.assertFalse(self.lab.path.exists())


class CaptureTests(unittest.TestCase):
    @staticmethod
    def capture(ttl=64, payload=b'test'):
        udp = struct.pack('!HHHH', 1234, 4321, len(payload)+8, 0)+payload
        ip = bytes([0x45, 0])+struct.pack('!H', 20+len(udp))+b'\0\0\0\0'+bytes([ttl,17])+b'\0\0'+bytes([10,1,2,3,10,1,2,4])
        frame = b'\0'*12+b'\x08\x00'+ip+udp
        return struct.pack('<IHHIIII', 0xa1b2c3d4, 2,4,0,0,65535,1)+struct.pack('<IIII',0,0,len(frame),len(frame))+frame

    def test_captured_payload_and_hop_limit_are_read_from_packet(self):
        packet = pcap.udp_packets(self.capture(ttl=63))[0]
        self.assertEqual(packet, {'source': '0a010203', 'ttl': 63, 'payload': b'test'})

    def test_truncated_evidence_is_rejected(self):
        for data in (b'', self.capture()[:-1], self.capture()[:30]):
            with self.assertRaises(ValueError):
                pcap.udp_packets(data)

    def test_mac_run_reports_blocked_and_never_passes(self):
        with tempfile.TemporaryDirectory() as output:
            argv = ['run.py', '--scenario', 'packet-fidelity', '--run-id', 'unit-blocked',
                    '--out-dir', output, '--source-sha', 'a'*40]
            with patch.object(sys, 'argv', argv), patch.object(sys, 'platform', 'darwin'), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(vm_run.main(), 1)
            envelope = json.loads((Path(output)/'result.json').read_text())
            self.assertEqual(envelope['status'], 'blocked')
            self.assertEqual(envelope['checks'], [])


class PacketEngineTests(unittest.TestCase):
    def test_cleanup_terminates_an_owned_process_group(self):
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], start_new_session=True)
        try:
            self.assertTrue(packet_engine.group_exists(child.pid))
            self.assertTrue(packet_engine.stop_group(child))
            self.assertFalse(packet_engine.group_exists(child.pid))
        finally:
            if child.poll() is None:
                child.kill()
                child.wait()

    def test_zero_tests_cannot_pass_even_with_existing_capture(self):
        with tempfile.TemporaryDirectory() as output:
            directory = Path(output)
            (directory/'test-output.txt').write_text('running 0 tests\ntest result: ok. 0 passed; 0 failed; 0 ignored;')
            (directory/'capture.tshark.json').write_text('[{"packet":1}]')
            case = {'id': 'case', 'testSelector': 'exact_test', 'artifacts': ['capture.tshark.json']}
            checks = packet_engine.validate_case(directory, case)
            self.assertFalse(next(c['passed'] for c in checks if c['id'].endswith('exact_test_executed')))

    def test_skip_message_rejects_rust_early_return_success(self):
        with tempfile.TemporaryDirectory() as output:
            directory = Path(output)
            (directory/'test-output.txt').write_text('skipping exact_test because disabled\ntest exact_test ... ok\n'
                                                     'test result: ok. 1 passed; 0 failed; 0 ignored;')
            (directory/'capture.tshark.json').write_text('[]')
            case = {'id': 'case', 'testSelector': 'exact_test', 'artifacts': ['capture.tshark.json']}
            checks = packet_engine.validate_case(directory, case)
            self.assertFalse(next(c['passed'] for c in checks if c['id'].endswith('exact_test_executed')))
            self.assertFalse(next(c['passed'] for c in checks if c['id'].endswith('captured_packets')))


class CancellationTests(unittest.TestCase):
    def test_pid_reuse_or_changed_command_cannot_authorize_signal(self):
        recorded = {'pid': 123, 'start_ticks': '5000', 'command_sha256': 'old'}
        self.assertTrue(cancel.matches(recorded, dict(recorded)))
        self.assertFalse(cancel.matches(recorded, dict(recorded, start_ticks='9000')))
        self.assertFalse(cancel.matches(recorded, dict(recorded, command_sha256='new')))

    def test_cancel_checks_root_registry_identity_before_signaling(self):
        with tempfile.TemporaryDirectory() as output:
            path = Path(output)/'owner.json'
            record = {'pid': 123, 'start_ticks': '5000', 'command_sha256': 'old',
                      'run_id': 'ours', 'out_dir': str(Path(output).resolve())}
            path.write_text(json.dumps(record))
            with patch.object(cancel, 'registry_path', return_value=path), \
                 patch.object(cancel, 'process_identity', return_value=dict(record, start_ticks='9000')), \
                 patch.object(cancel.os, 'kill') as kill:
                with self.assertRaisesRegex(ValueError, 'reused PID'):
                    cancel.cancel('ours', Path(output))
                kill.assert_not_called()


if __name__ == '__main__':
    unittest.main()
