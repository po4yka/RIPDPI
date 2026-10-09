#!/usr/bin/env python3
"""Run-owned Linux namespaces with a routed data path and a separate control link."""
from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import sys

PROFILES = ('baseline', 'drop', 'delay', 'loss', 'reorder', 'udp-block',
            'tcp-app-blackhole', 'mtu-blackhole', 'ipv6-block')
REGISTRY = Path('/run/ripdpi-acceptance')


def run(*args: str, stdin: str | None = None, check: bool = True) -> str:
    result = subprocess.run(args, input=stdin, text=True, capture_output=True, check=False)
    if check and result.returncode:
        raise RuntimeError(f'{args[0]} failed ({result.returncode}): {result.stderr.strip()}')
    return result.stdout


def identity(run_id: str) -> dict:
    if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}', run_id):
        raise ValueError('run-id must contain 1..64 safe characters')
    digest = hashlib.sha256(run_id.encode()).hexdigest()
    stem = 'ra' + digest[:8]
    slot = 1 + int(digest[8:12], 16) % 250
    prefix = f'10.203.{slot}'
    v6 = f'fd42:{int(digest[:4], 16):x}:{int(digest[4:8], 16):x}'
    return dict(run_id=run_id, stem=stem, slot=slot, router=stem+'r', peer=stem+'p',
                client=stem+'c', host_link=stem+'h', management_link=stem+'m',
                host_ipv4=prefix+'.1', router_ingress_ipv4=prefix+'.2',
                router_peer_ipv4=prefix+'.5', peer_ipv4=prefix+'.6',
                host_management_ipv4=prefix+'.9', peer_management_ipv4=prefix+'.10',
                router_client_ipv4=prefix+'.13', client_ipv4=prefix+'.14',
                peer_subnet=prefix+'.4/30', ingress_subnet=prefix+'.0/30',
                client_subnet=prefix+'.12/30', peer_ipv6=v6+':1::2',
                router_peer_ipv6=v6+':1::1', client_ipv6=v6+':2::2',
                router_client_ipv6=v6+':2::1', peer_subnet6=v6+':1::/64',
                client_subnet6=v6+':2::/64')


def ports(value: str) -> list[int]:
    result = [int(part) for part in value.split(',') if part]
    if not result or len(result) != len(set(result)) or any(p < 1024 or p > 65535 for p in result):
        raise ValueError('ports must be unique unprivileged port numbers')
    return result


def fault_rules(profile: str) -> str:
    if profile not in PROFILES:
        raise ValueError('unknown fault profile')
    rule = {
        'drop': 'counter name fault_drop drop',
        'udp-block': 'meta l4proto udp counter name fault_drop drop',
        # Arm this AFTER the protocol/application handshake. This is a timed
        # path blackhole, not a claim to recognize encrypted application data.
        'tcp-app-blackhole': 'meta l4proto tcp counter name fault_drop drop',
        'mtu-blackhole': 'meta length > 1280 counter name fault_drop drop',
        'ipv6-block': 'meta nfproto ipv6 counter name fault_drop drop',
    }.get(profile, '')
    return f'''table inet acceptance {{
 counter fault_drop {{ }}
 counter forwarded {{ }}
 chain forward {{ type filter hook forward priority 0; policy drop;
  {rule}
  iifname {{ "ingress", "client" }} oifname "peer" counter name forwarded accept
  iifname "peer" oifname {{ "ingress", "client" }} ct state established,related counter name forwarded accept
 }}
}}
'''


class Router:
    def __init__(self, run_id: str, output: Path):
        self.data = identity(run_id)
        self.output = output.resolve()
        self.path = REGISTRY / (self.data['stem'] + '.json')

    def save(self) -> None:
        temporary = self.path.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.data, indent=2) + '\n')
        temporary.replace(self.path)
        self.output.mkdir(parents=True, exist_ok=True)
        (self.output / 'topology.json').write_text(json.dumps(self.data, indent=2) + '\n')

    def load(self) -> None:
        state = json.loads(self.path.read_text())
        if state['run_id'] != self.data['run_id'] or state['output'] != str(self.output):
            raise ValueError('topology belongs to another run or output directory')
        self.data = state

    def ns(self, namespace: str, *args: str, **kwargs) -> str:
        return run('ip', 'netns', 'exec', self.data[namespace], *args, **kwargs)

    def up(self, peer_pid: int | None = None) -> None:
        REGISTRY.mkdir(mode=0o700, exist_ok=True)
        if self.path.exists():
            raise ValueError('run already exists; refusing to replace topology')
        existing = run('ip', 'netns', 'list')
        if any(self.data[n] in existing.split() for n in ('router', 'peer', 'client')):
            raise ValueError('namespace name collision')
        if json.loads(run('ip', '-j', 'route', 'show', self.data['peer_subnet'])):
            raise ValueError('address slot is in use; choose a different run-id')
        self.data.update(output=str(self.output), namespaces=[], links=[], published=False,
                         peer_adopted=peer_pid is not None)
        # Claim the registry atomically before any mutation. Two invocations
        # with the same run ID must not both pass the existence preflight.
        with self.path.open('x') as handle:
            json.dump(self.data, handle)
        try:
            self.save()
            for role in ('router', 'peer', 'client'):
                name = self.data[role]
                if role == 'peer' and peer_pid is not None:
                    if peer_pid <= 1:
                        raise ValueError('peer PID must refer to a network=none container')
                    target = os.readlink(f'/proc/{peer_pid}/ns/net')
                    if target == os.readlink('/proc/self/ns/net'):
                        raise ValueError('refusing to use the host network namespace')
                    run('ip', 'netns', 'attach', name, str(peer_pid))
                else:
                    run('ip', 'netns', 'add', name)
                self.data['namespaces'].append(role)
                self.save()
                if role == 'peer' and peer_pid is not None and json.loads(self.ns(role, 'ip', '-j', 'route')):
                    raise ValueError('adopted peer must not have any routes')
                self.ns(role, 'ip', 'link', 'set', 'lo', 'up')
            self.link(None, self.data['host_link'], 'router', 'ingress')
            self.link('router', 'peer', 'peer', 'data')
            self.link('router', 'client', 'client', 'data')
            self.link(None, self.data['management_link'], 'peer', 'management')
            for role, dev, key, mask in (
                (None, self.data['host_link'], 'host_ipv4', '30'),
                ('router', 'ingress', 'router_ingress_ipv4', '30'),
                ('router', 'peer', 'router_peer_ipv4', '30'), ('peer', 'data', 'peer_ipv4', '30'),
                (None, self.data['management_link'], 'host_management_ipv4', '30'),
                ('peer', 'management', 'peer_management_ipv4', '30'),
                ('router', 'client', 'router_client_ipv4', '30'), ('client', 'data', 'client_ipv4', '30'),
                ('router', 'peer', 'router_peer_ipv6', '64'), ('peer', 'data', 'peer_ipv6', '64'),
                ('router', 'client', 'router_client_ipv6', '64'), ('client', 'data', 'client_ipv6', '64')):
                self.call(role, 'ip', 'addr', 'add', self.data[key]+'/'+mask, 'dev', dev,
                          *(['nodad'] if 'ipv6' in key else []))
            self.ns('router', 'sysctl', '-qw', 'net.ipv4.ip_forward=1', 'net.ipv6.conf.all.forwarding=1')
            run('ip', 'route', 'add', self.data['peer_subnet'], 'via', self.data['router_ingress_ipv4'])
            self.data['host_route'] = True
            self.save()
            for subnet in ('ingress_subnet', 'client_subnet'):
                self.ns('peer', 'ip', 'route', 'add', self.data[subnet], 'via', self.data['router_peer_ipv4'])
            self.ns('client', 'ip', 'route', 'add', self.data['peer_subnet'], 'via', self.data['router_client_ipv4'])
            self.ns('client', 'ip', '-6', 'route', 'add', self.data['peer_subnet6'], 'via', self.data['router_client_ipv6'])
            self.ns('peer', 'ip', '-6', 'route', 'add', self.data['client_subnet6'], 'via', self.data['router_peer_ipv6'])
            self.apply('baseline')
        except BaseException:
            self.down()
            raise

    def call(self, role: str | None, *args: str) -> str:
        return self.ns(role, *args) if role else run(*args)

    def link(self, left: str | None, left_dev: str, right: str, right_dev: str) -> None:
        # Create both ends in the left namespace; only run-owned names are used.
        temporary = self.data['stem'] + 'v'
        self.call(left, 'ip', 'link', 'add', left_dev, 'type', 'veth', 'peer', 'name', temporary)
        if left is None:
            self.data['links'].append(left_dev)
            self.save()
        self.call(left, 'ip', 'link', 'set', temporary, 'netns', self.data[right])
        self.ns(right, 'ip', 'link', 'set', temporary, 'name', right_dev)
        # Every link is private to this run. Disable DAD before link-up so
        # link-local router neighbor discovery cannot race the first probe.
        self.call(left, 'sysctl', '-qw', f'net.ipv6.conf.{left_dev}.accept_dad=0')
        self.ns(right, 'sysctl', '-qw', f'net.ipv6.conf.{right_dev}.accept_dad=0')
        self.call(left, 'ip', 'link', 'set', left_dev, 'up')
        self.ns(right, 'ip', 'link', 'set', right_dev, 'up')
        for role, dev in ((left, left_dev), (right, right_dev)):
            self.call(role, 'ethtool', '-K', dev, 'tso', 'off', 'gso', 'off', 'gro', 'off')

    def apply(self, profile: str) -> None:
        self.load()
        rules = fault_rules(profile)
        # nft commits the delete/recreate batch atomically, so there is no gap.
        if self.data.get('profile'):
            rules = 'delete table inet acceptance\n' + rules
        self.ns('router', 'nft', '-f', '-', stdin=rules)
        netem = {'delay': ['delay', '80ms', '10ms'], 'loss': ['loss', '30%'],
                 'reorder': ['delay', '20ms', 'reorder', '50%', '50%']} .get(profile, [])
        for dev in ('peer', 'client', 'ingress'):
            if netem:
                self.ns('router', 'tc', 'qdisc', 'replace', 'dev', dev, 'root', 'netem', *netem)
            else:
                self.ns('router', 'tc', 'qdisc', 'del', 'dev', dev, 'root', check=False)
        self.data['profile'] = profile
        self.save()

    def publish(self, ingress: str, data_ports: list[int], control_ports: list[int]) -> None:
        self.load()
        addr = ipaddress.ip_address(ingress)
        if addr.version != 4 or not addr.is_private or addr.is_loopback or addr.is_unspecified:
            raise ValueError('ingress must be the private VM IPv4, not a wildcard or loopback')
        if set(data_ports) & set(control_ports):
            raise ValueError('control and data ports must be different')
        if self.data['published']:
            raise ValueError('ports already published')
        assigned = {a['local'] for link in json.loads(run('ip', '-j', '-4', 'addr')) for a in link.get('addr_info', [])}
        if ingress not in assigned:
            raise ValueError('ingress address is not assigned to this VM')
        lock = REGISTRY / 'publish-owner.json'
        with lock.open('x') as handle:
            json.dump({'run_id': self.data['run_id']}, handle)
        self.data['published'] = True
        self.data['forward_before'] = run('sysctl', '-n', 'net.ipv4.ip_forward').strip()
        self.save()
        table = self.data['stem']
        rules = [f'table ip {table} {{', 'chain input_nat { type nat hook prerouting priority dstnat; policy accept;']
        for destination, pp in ((self.data['peer_ipv4'], data_ports), (self.data['peer_management_ipv4'], control_ports)):
            if pp:
                values = ', '.join(map(str, pp))
                for protocol in ('tcp', 'udp'):
                    rules.append(f'ip daddr {ingress} {protocol} dport {{ {values} }} counter dnat to {destination}')
        rules.extend(['}', 'chain return_nat { type nat hook postrouting priority srcnat; policy accept;'])
        for destination, source, link in ((self.data['peer_ipv4'], self.data['host_ipv4'], self.data['host_link']),
                                          (self.data['peer_management_ipv4'], self.data['host_management_ipv4'], self.data['management_link'])):
            rules.append(f'ip daddr {destination} oifname "{link}" counter snat to {source}')
        rules.extend(['}', '}'])
        try:
            run('nft', '-f', '-', stdin='\n'.join(rules))
            self.data['nft_published'] = True
            self.save()
            # Docker sets the root FORWARD policy to DROP. A dedicated chain
            # permits only these endpoints without changing that policy.
            run('iptables', '-N', table)
            self.data['filter_chain'] = True
            self.save()
            for destination, link, pp in ((self.data['peer_ipv4'], self.data['host_link'], data_ports),
                                          (self.data['peer_management_ipv4'], self.data['management_link'], control_ports)):
                for protocol in ('tcp', 'udp'):
                    for port in pp:
                        run('iptables', '-A', table, '-o', link, '-d', destination, '-p', protocol,
                            '--dport', str(port), '-j', 'ACCEPT')
                run('iptables', '-A', table, '-i', link, '-s', destination,
                    '-m', 'conntrack', '--ctstate', 'ESTABLISHED,RELATED', '-j', 'ACCEPT')
            run('iptables', '-I', 'FORWARD', '1', '-m', 'comment', '--comment', table, '-j', table)
            self.data['filter_jump'] = True
            self.save()
            run('sysctl', '-qw', 'net.ipv4.ip_forward=1')
            self.data.update(ingress_ipv4=ingress, data_ports=data_ports, control_ports=control_ports)
            self.save()
        except BaseException:
            self.unpublish()
            raise

    def unpublish(self) -> None:
        if not self.data.get('published'):
            return
        lock = REGISTRY / 'publish-owner.json'
        if json.loads(lock.read_text()).get('run_id') != self.data['run_id']:
            raise ValueError('publication lock belongs to another run')
        table = self.data['stem']
        if self.data.get('filter_jump'):
            run('iptables', '-D', 'FORWARD', '-m', 'comment', '--comment', table, '-j', table)
            self.data['filter_jump'] = False
            self.save()
        if self.data.get('filter_chain'):
            run('iptables', '-F', table)
            run('iptables', '-X', table)
            self.data['filter_chain'] = False
            self.save()
        if self.data.get('nft_published'):
            run('nft', 'delete', 'table', 'ip', table)
            self.data['nft_published'] = False
            self.save()
        run('sysctl', '-qw', 'net.ipv4.ip_forward=' + self.data['forward_before'])
        lock.unlink()
        self.data['published'] = False
        self.save()

    def status(self) -> dict:
        self.load()
        return {'topology': self.data, 'firewall': json.loads(self.ns('router', 'nft', '-j', 'list', 'table', 'inet', 'acceptance')),
                'qdisc': json.loads(self.ns('router', 'tc', '-s', '-j', 'qdisc', 'show')),
                'peer_routes': json.loads(self.ns('peer', 'ip', '-j', 'route')),
                'peer_routes6': json.loads(self.ns('peer', 'ip', '-j', '-6', 'route'))}

    def down(self) -> None:
        self.load()
        # Never kill a container or peer daemon on behalf of its owner.
        for role in self.data['namespaces']:
            if role == 'peer' and self.data['peer_adopted']:
                continue
            if run('ip', 'netns', 'pids', self.data[role]).strip():
                raise RuntimeError('stop processes in ' + self.data[role] + ' before cleanup')
        self.unpublish()
        if self.data.get('host_route'):
            run('ip', 'route', 'del', self.data['peer_subnet'], 'via', self.data['router_ingress_ipv4'])
            self.data['host_route'] = False
            self.save()
        for link in list(self.data['links']):
            run('ip', 'link', 'del', link)
            self.data['links'].remove(link)
            self.save()
        for role in list(reversed(self.data['namespaces'])):
            run('ip', 'netns', 'del', self.data[role])
            self.data['namespaces'].remove(role)
            self.save()
        self.path.unlink()
        (self.output / 'cleanup.json').write_text(json.dumps({'run_id': self.data['run_id'], 'passed': True})+'\n')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['up', 'down', 'apply', 'status', 'publish', 'unpublish'])
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--out-dir', required=True, type=Path)
    parser.add_argument('--profile', choices=PROFILES, default='baseline')
    parser.add_argument('--peer-pid', type=int)
    parser.add_argument('--ingress-ip')
    parser.add_argument('--ports', type=ports, default=[])
    parser.add_argument('--control-ports', type=ports, default=[])
    args = parser.parse_args()
    if sys.platform != 'linux' or os.geteuid() != 0:
        raise RuntimeError('router requires Linux root inside a disposable acceptance VM')
    router = Router(args.run_id, args.out_dir)
    if args.action == 'up':
        router.up(args.peer_pid)
    elif args.action == 'apply':
        router.apply(args.profile)
    elif args.action == 'publish':
        if not args.ingress_ip or not args.ports:
            raise ValueError('publish requires --ingress-ip and --ports')
        router.publish(args.ingress_ip, args.ports, args.control_ports)
    elif args.action == 'status':
        print(json.dumps(router.status(), indent=2))
    elif args.action == 'unpublish':
        router.load()
        router.unpublish()
    else:
        router.down()
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
