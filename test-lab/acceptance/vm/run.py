#!/usr/bin/env python3
"""Prove Linux router fault placement with real sockets and run-owned cleanup."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import subprocess
import sys
import time

from router import Router, run
from requirements import required_checks
import cancel

HERE = Path(__file__).resolve().parent
SCENARIOS = {'routed-baseline-drop-recovery': 'drop', 'routed-udp-block': 'udp-block',
             'routed-tcp-app-blackhole': 'tcp-app-blackhole', 'routed-delay': 'delay',
             'routed-loss': 'loss', 'routed-reorder': 'reorder',
             'routed-mtu-blackhole': 'mtu-blackhole', 'routed-ipv6-block': 'ipv6-block',
             'packet-fidelity': 'drop', 'packet-engine': None}
REQUIRED_CHECKS = {scenario: required_checks(scenario) for scenario in SCENARIOS}


def stop(process):
    if process and process.poll() is None:
        process.send_signal(signal.SIGTERM)
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def source_sha(override: str | None) -> str:
    sha = override or os.environ.get('RIPDPI_ACCEPTANCE_SOURCE_SHA')
    if not sha:
        sha = run('git', '-C', str(HERE.parents[2]), 'rev-parse', 'HEAD').strip()
    if not re.fullmatch('[0-9a-f]{40}', sha):
        raise ValueError('source SHA must be the exact 40-character checkout commit')
    return sha


def execute(args, result):
    output = args.out_dir.resolve()
    router = Router(args.run_id, output)
    endpoints = None
    server = None
    held_tcp = None
    created = False
    captures = []
    checks = result['checks']
    result['cleanup']['passed'] = False

    def check(name, passed):
        checks.append({'id': name, 'passed': bool(passed)})

    def probe(label, family='ipv4', protocol='tcp', count=1, payload=None, timeout=0.35, control=False):
        address = router.data['peer_management_ipv4' if control else 'peer_'+family]
        key = ('control' if control else family)+'_'+protocol
        cmd = ['python3', str(HERE / 'traffic.py'), 'probe', '--host', address,
               '--port', str(endpoints[key]), '--protocol', protocol,
               '--payload', payload or args.run_id+'-'+label, '--count', str(count), '--timeout', str(timeout)]
        measured = json.loads(run(*cmd) if control else router.ns('client', *cmd))
        (output / (label+'.json')).write_text(json.dumps(measured, indent=2)+'\n')
        return measured

    try:
        router.up()
        created = True
        log = (output / 'traffic.log').open('w')
        server = subprocess.Popen(['ip', 'netns', 'exec', router.data['peer'], 'python3', str(HERE/'traffic.py'),
                                   'serve', '--host', router.data['peer_ipv4'], '--ipv6', router.data['peer_ipv6'],
                                   '--control', router.data['peer_management_ipv4'], '--out-dir', str(output)],
                                  stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic()+10
        while not (output/'ready.json').exists():
            if server.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('local socket endpoints did not become ready')
            time.sleep(0.05)
        endpoints = json.loads((output/'ready.json').read_text())
        for side in ('client', 'peer'):
            path = output/(side+'.pcap')
            # These PCAPs contain generated fixture traffic, stay local, and
            # are never offered as packet evidence from the Android emulator.
            capture_log = (output/(side+'-capture.log')).open('w')
            captures.append(subprocess.Popen(['ip', 'netns', 'exec', router.data['router'], 'tcpdump', '-U',
                                              '-i', side, '-w', str(path), 'tcp or udp'],
                                             stdout=capture_log, stderr=subprocess.STDOUT))
        time.sleep(0.3)
        check('capture_processes_ready', all(p.poll() is None for p in captures))
        for family in ('ipv4', 'ipv6'):
            for protocol in ('tcp', 'udp'):
                check('baseline_'+family+'_'+protocol, probe('baseline-'+family+'-'+protocol, family, protocol, timeout=3)['received'] == 1)
        check('management_baseline', probe('control-baseline', control=True, timeout=3)['received'] == 1)
        status = router.status()
        check('no_public_route_ipv4', not any(r.get('dst') == 'default' for r in status['peer_routes']))
        check('no_public_route_ipv6', not any(r.get('dst') == 'default' for r in status['peer_routes6']))
        # Prove the active network namespace cannot even route external traffic.
        external = subprocess.run(['ip', 'netns', 'exec', router.data['peer'], 'ip', 'route', 'get', '198.51.100.1'], capture_output=True)
        check('external_data_egress_unreachable', external.returncode != 0)
        profile = SCENARIOS[args.scenario]
        if profile == 'tcp-app-blackhole':
            script = '''import json,socket,sys
s=socket.create_connection((HOST, PORT),timeout=1)
s.sendall(b'pre-established-application-baseline')
assert s.recv(1024)==b'pre-established-application-baseline'
print('ready',flush=True)
sys.stdin.readline()
s.sendall(b'application-after-fault')
try:
 data=s.recv(1024)
 print(json.dumps({'blocked':False}),flush=True)
except TimeoutError:
 print(json.dumps({'blocked':True}),flush=True)
'''.replace('HOST', repr(router.data['peer_ipv4'])).replace('PORT', str(endpoints['ipv4_tcp']))
            held_tcp = subprocess.Popen(['ip', 'netns', 'exec', router.data['client'], 'python3', '-c', script],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            readable, _, _ = select.select([held_tcp.stdout], [], [], 5)
            if not readable or held_tcp.stdout.readline().strip() != 'ready':
                raise RuntimeError('pre-established TCP application baseline failed')
            check('tcp_application_established_before_fault', True)
        router.apply(profile)
        if held_tcp:
            text, error = held_tcp.communicate('\n', timeout=5)
            if held_tcp.returncode:
                raise RuntimeError('established TCP fault probe failed: '+error)
            observed = json.loads(text)
            (output/'established-tcp.json').write_text(json.dumps(observed)+'\n')
            check('established_tcp_application_blackholed', observed['blocked'])
        if profile in ('delay', 'loss', 'reorder'):
            count = 40 if profile == 'loss' else 10
            measured = probe('fault-udp', protocol='udp', count=count, timeout=0.45)
            if profile == 'delay':
                check('delay_observed', measured['received'] == count and min(measured['elapsed_seconds']) > 0.08)
            elif profile == 'loss':
                check('loss_observed', 0 < measured['received'] < count)
            else:
                # Sequential echo probes confirm usable netem path, not packet
                # ordering. Reordering is separately measured by a burst below.
                check('reorder_path_usable', measured['received'] > 0)
                check('reorder_observed', reorder_probe(router, endpoints['ipv4_udp'], output))
        elif profile == 'mtu-blackhole':
            check('small_udp_passes', probe('small-udp', protocol='udp')['received'] == 1)
            check('large_udp_dropped', probe('large-udp', protocol='udp', payload='M'*1400)['received'] == 0)
        else:
            tcp_expected = profile in ('udp-block', 'ipv6-block')
            udp_expected = profile in ('tcp-app-blackhole', 'ipv6-block')
            check('fault_tcp', bool(probe('fault-tcp')['received']) == tcp_expected)
            check('fault_udp', bool(probe('fault-udp', protocol='udp')['received']) == udp_expected)
            if profile == 'ipv6-block':
                check('ipv6_blocked', probe('fault-ipv6', family='ipv6', protocol='udp')['received'] == 0)
        check('management_survives_fault', probe('control-fault', control=True, timeout=3)['received'] == 1)
        fault = router.status()
        (output/'fault-status.json').write_text(json.dumps(fault, indent=2)+'\n')
        counters = [n['counter'] for n in fault['firewall']['nftables'] if 'counter' in n]
        if profile not in ('delay', 'loss', 'reorder'):
            check('fault_counter_incremented', any(c['name'] == 'fault_drop' and c['packets'] > 0 for c in counters))
        else:
            check('netem_present', any(q['kind'] == 'netem' for q in fault['qdisc']))
        router.apply('baseline')
        for family in ('ipv4', 'ipv6'):
            for protocol in ('tcp', 'udp'):
                check('recovery_'+family+'_'+protocol, probe('recovery-'+family+'-'+protocol, family, protocol, timeout=3)['received'] == 1)
        (output/'recovery-status.json').write_text(json.dumps(router.status(), indent=2)+'\n')
        receipts = [json.loads(line)['payload'] for line in (output/'receipts.jsonl').read_text().splitlines()]
        check('peer_receipts_match_baseline_and_recovery', all(
            args.run_id+'-'+phase+'-'+family+'-'+protocol in receipts
            for phase in ('baseline', 'recovery') for family in ('ipv4', 'ipv6') for protocol in ('tcp', 'udp')))
    finally:
        for capture in captures:
            if capture.poll() is None:
                capture.send_signal(signal.SIGINT)
                try:
                    capture.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    stop(capture)
        stop(server)
        stop(held_tcp)
        if created and router.path.exists():
            router.down()
        result['cleanup']['passed'] = created and not router.path.exists()
    if args.scenario == 'packet-fidelity':
        verify_capture_path(output, check, args.run_id)


def reorder_probe(router, port, output):
    script = '''import socket,time,json
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
s.settimeout(0.4)
s.connect((HOST, PORT))
for i in range(80):
 s.send(str(i).encode()); time.sleep(0.001)
received=[]
while True:
 try: received.append(int(s.recv(100)))
 except TimeoutError: break
print(json.dumps(received))
'''.replace('HOST', repr(router.data['peer_ipv4'])).replace('PORT', str(port))
    received = json.loads(router.ns('client', 'python3', '-c', script))
    (output/'reorder-sequence.json').write_text(json.dumps(received)+'\n')
    return len(received) >= 2 and any(b < a for a, b in zip(received, received[1:]))


def verify_capture_path(output, check, run_id):
    # A router must preserve UDP payload while it changes the hop limit.
    from pcap import udp_packets
    left = udp_packets((output/'client.pcap').read_bytes())
    right = udp_packets((output/'peer.pcap').read_bytes())
    payload = (run_id+'-baseline-ipv4-udp').encode()
    before = [p for p in left if p['payload'] == payload]
    after = [p for p in right if p['payload'] == payload]
    check('native_udp_payload_on_both_router_sides', bool(before and after))
    check('native_router_hop_limit_decrement', any(a['ttl'] == b['ttl']+1 and a['source'] == b['source']
                                                 for a in before for b in after))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=SCENARIOS, required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--out-dir', type=Path, required=True)
    parser.add_argument('--source-sha')
    args = parser.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)
    if (args.out_dir/'result.json').exists() or (args.out_dir/'topology.json').exists():
        parser.error('output already contains run evidence; use a fresh directory')
    result = dict(schema_version=1, run_id=args.run_id, scenario_id=args.scenario,
                  source_sha='', status='blocked', tier='packet-fidelity' if args.scenario in ('packet-fidelity', 'packet-engine') else 'routed-fault',
                  checks=[], artifacts=[], cleanup={'passed': True})
    registration = None
    def interrupted(signum, _frame):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        raise RuntimeError('acceptance cancelled by signal '+str(signum))
    old_term = signal.signal(signal.SIGTERM, interrupted)
    old_int = signal.signal(signal.SIGINT, interrupted)
    try:
        result['source_sha'] = source_sha(args.source_sha)
        missing = [c for c in ('ip', 'nft', 'tc', 'tcpdump', 'ethtool') if not shutil.which(c)]
        if sys.platform != 'linux' or os.geteuid() != 0 or missing:
            raise ValueError('Linux root and ip/nft/tc/tcpdump/ethtool are required; run inside the prepared VM')
        registration = cancel.register(args.run_id, args.out_dir)
        result['status'] = 'failed'
        if args.scenario == 'packet-engine':
            from packet_engine import execute as engine_execute
            engine_execute(args, result)
        else:
            execute(args, result)
        found = {c['id'] for c in result['checks']}
        missing_checks = set(required_checks(args.scenario)) - found
        if missing_checks:
            raise ValueError('required checks are missing: '+', '.join(sorted(missing_checks)))
        if result['checks'] and all(c['passed'] for c in result['checks']) and result['cleanup']['passed']:
            result['status'] = 'passed'
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        result['error'] = str(error)
    def private_capture(path):
        return path.suffix in ('.pcap', '.pcapng') or path.name == 'capture.tshark.json'
    result['artifacts'] = sorted(str(p.relative_to(args.out_dir)) for p in args.out_dir.rglob('*')
                                 if p.is_file() and not private_capture(p) and p.name != 'result.json')
    result['private_artifacts'] = sorted(str(p.relative_to(args.out_dir)) for p in args.out_dir.rglob('*')
                                       if p.is_file() and private_capture(p))
    (args.out_dir/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    if registration:
        cancel.unregister(args.run_id, registration)
    signal.signal(signal.SIGTERM, old_term)
    signal.signal(signal.SIGINT, old_int)
    print(json.dumps(result))
    return 0 if result['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
