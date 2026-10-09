#!/usr/bin/env python3
"""Required checks shared by the VM adapter and top-level acceptance manifest."""
import argparse
import json
from pathlib import Path


def required_checks(scenario: str) -> list[str]:
    if scenario == 'packet-engine':
        registry = json.loads((Path(__file__).resolve().parents[3]/'scripts/ci/packet-smoke-scenarios.json').read_text())
        result = ['packet_engine_process_succeeded', 'eight_generated_cases_executed', 'engine_no_orphan_processes']
        for case in registry:
            if case['lane'] == 'cli' and not case.get('generatedTemplate'):
                result.extend(case['id']+':'+name for name in case['artifacts'])
                result.extend([case['id']+':exact_test_executed', case['id']+':captured_packets'])
        return result
    result = ['capture_processes_ready', 'management_baseline', 'no_public_route_ipv4',
              'no_public_route_ipv6', 'external_data_egress_unreachable',
              'management_survives_fault', 'peer_receipts_match_baseline_and_recovery']
    result.extend(phase+'_'+family+'_'+protocol for phase in ('baseline', 'recovery')
                  for family in ('ipv4', 'ipv6') for protocol in ('tcp', 'udp'))
    extra = {
        'routed-baseline-drop-recovery': ['fault_tcp', 'fault_udp', 'fault_counter_incremented'],
        'routed-udp-block': ['fault_tcp', 'fault_udp', 'fault_counter_incremented'],
        'routed-tcp-app-blackhole': ['fault_tcp', 'fault_udp', 'fault_counter_incremented',
                                   'tcp_application_established_before_fault', 'established_tcp_application_blackholed'],
        'routed-delay': ['delay_observed', 'netem_present'],
        'routed-loss': ['loss_observed', 'netem_present'],
        'routed-reorder': ['reorder_path_usable', 'reorder_observed', 'netem_present'],
        'routed-mtu-blackhole': ['small_udp_passes', 'large_udp_dropped', 'fault_counter_incremented'],
        'routed-ipv6-block': ['fault_tcp', 'fault_udp', 'fault_counter_incremented', 'ipv6_blocked'],
        'packet-fidelity': ['fault_tcp', 'fault_udp', 'fault_counter_incremented',
                            'native_udp_payload_on_both_router_sides', 'native_router_hop_limit_decrement'],
    }
    return result + extra[scenario]


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', required=True)
    print(json.dumps(required_checks(parser.parse_args().scenario)))
