#!/usr/bin/env python3
"""Generate a synthetic regression document with the current backend materializer.

Only context and external authority boundaries are replaced. The materializer,
transport validation, selector construction, and serialization run unchanged.
This proves serialization inputs for an app regression, not live connectivity.
"""
from pathlib import Path
from copy import deepcopy
import argparse
import base64
import hashlib
import json
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--backend', type=Path, required=True)
args = parser.parse_args()
BACKEND = args.backend.resolve()
OUTPUT = Path(__file__).resolve().parents[2] / 'core/data/src/test/resources/deployment-generated'
OUTPUT.mkdir(parents=True, exist_ok=True)
sha = subprocess.check_output(['git', '-C', str(BACKEND), 'rev-parse', 'HEAD'], text=True).strip()
expected_sha = '34c61b400998b5682056ac00e52cb016ae4ff228'
assert sha == expected_sha, 'Backend revision changed; review fixture provenance before regeneration'
dirty = subprocess.check_output(
    ['git', '-C', str(BACKEND), 'status', '--porcelain', '--untracked-files=no'], text=True)
assert not dirty, 'Backend tracked sources changed; use a clean pinned checkout'
sys.path.insert(0, str(BACKEND / 'scripts'))
import client_payload

context = {
    'vpn': {'enable_xray_reality': True, 'enable_nginx_xhttp': True,
            'enable_hysteria': True, 'enable_amneziawg': False, 'enable_snell': True},
    'xray_port': 443, 'xray_fallback_port': 0,
    'p0_reality_flow_mode': 'vision',
    'p0_reality_shapes': {'vision': {'finalmask': False, 'client_mux': False,
                                    'client_flow': 'xtls-rprx-vision'}},
    'nginx_xhttp_public_port': 8443, 'nginx_xhttp_fallback_port': 2083,
    'hysteria_port': 443,
    'snell': {'variants': [{'id': 'v4-stream', 'listen_port': 9443, 'client_version': 4}]},
}
document = {
    'xray': {
        'version': 'v26.3.27', 'server_names': ['reality.example.test'],
        'reality_public_key': base64.urlsafe_b64encode(bytes(range(1, 33))).decode().rstrip('='),
        'clients': [{'name': 'fixture-device', 'uuid': '11111111-2222-4333-8444-555555555555',
                     'short_id': '0102030405060708'}],
        'xhttp_mode': 'stream-up', 'xhttp_path': '/fixture-sync',
    },
    'nginx_xhttp': {'server_name': 'direct.example.test', 'fallback_enabled': True,
                    'fallback_server_name': 'alternate.example.test',
                    'fallback_cert_pem': 'synthetic-not-a-certificate',
                    'fallback_key_pem': 'synthetic-not-a-private-key'},
    'hysteria': {'version': 'v2.13.0', 'server_name': 'hysteria.example.test',
                'masquerade_url': 'https://hysteria.example.test',
                'resource_profile': 'bandwidth-capped',
                'bandwidth_up': '100 mbps', 'bandwidth_down': '200 mbps',
                'salamander_enabled': True, 'salamander_password': 'synthetic-salamander',
                'clients': [{'name': 'fixture-device', 'password': 'synthetic-hysteria'}]},
    'snell_secrets': {'variants': [{'id': 'v4-stream', 'psk': 'synthetic-snell-psk',
                        'users': [{'name': 'fixture-device', 'userkey': 'synthetic-snell-userkey'}]}]},
}
command_calls = []
def external_metadata(argv, **kwargs):
    assert argv == [str(BACKEND / 'scripts/terraform-env.sh'), 'output', '-raw', 'server_ipv4']
    command_calls.append('synthetic terraform server_ipv4')
    return b'203.0.113.10'
def refuse_external_authority(*args, **kwargs):
    raise AssertionError('Unexpected external authority read')
client_payload.context = lambda cohort: deepcopy(context) if cohort == 'fixture' else refuse_external_authority()
client_payload.command = external_metadata
client_payload.read_document = refuse_external_authority
client_payload.inventory_cohort = refuse_external_authority
source = '/synthetic-rollout-authority'
options = client_payload.options('ripdpi', hosts=['upcloud:fixture'], cohorts=['fixture'],
                                  awg_host='', expires='', utls='chrome')
payload, bindings = client_payload.materialize('fixture-device', options,
    documents={source: deepcopy(document)}, source_paths=[source], extended=True)
assert bindings == []
assert command_calls == ['synthetic terraform server_ipv4']
out = OUTPUT / 'bundle.json'
out.write_text(json.dumps(payload, indent=2) + '\n')
metadata = {
    'backend_commit': sha,
    'materializer': 'scripts/client_payload.py',
    'materializer_sha256': hashlib.sha256((BACKEND / 'scripts/client_payload.py').read_bytes()).hexdigest(),
    'generator': 'scripts/tests/generate_deployment_bundle_fixture.py',
    'payload_sha256': hashlib.sha256(out.read_bytes()).hexdigest(),
    'authority': 'synthetic only; no inventory, SOPS, real Terraform, server, or network calls',
    'asserted_boundary_calls': command_calls,
    'expected_activatable_tags': ['p0-reality-upcloud-fixture', 'p1-xhttp-upcloud-fixture',
                                'p1-xhttp-upcloud-fixture:alternate', 'p2-hysteria2-upcloud-fixture'],
    'expected_unsupported_tags': ['p3-snell-v4-stream-fresh-upcloud-fixture',
                                 'p3-snell-v4-stream-reuse-upcloud-fixture'],
    'expected_selector': 'select', 'expected_default': 'auto',
    'expected_nested_selector': 'snell-evaluation', 'amneziawg': 'not included',
    'validation_scope': 'current materializer serialization and input validation; no runtime acceptance',
}
(OUTPUT / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
print(json.dumps({'backend_commit': sha, 'payload': str(out), 'types': [n['type'] for n in payload['outbounds']],
                  'payload_sha256': metadata['payload_sha256']}))
