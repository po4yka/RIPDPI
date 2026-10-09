"""Exact native tests and independent peers; tiers describe evidence, not coverage claims."""

SCENARIOS = {}


def native(identifier, protocol, package, target, test, *, ignored=False):
    SCENARIOS[identifier] = {
        "protocol": protocol,
        "tier": "native-contract",
        "driver": "cargo",
        "package": package,
        "target": target,
        "test": test,
        "ignored": ignored,
        "checks": ["exact-test-executed"],
        "peer_identity": {"implementation": "repository-fixture"},
    }


for identifier, protocol, package, target, test in (
    (
        "native-tuic-tcp",
        "tuic_v5",
        "ripdpi-tuic",
        "loopback_e2e",
        "tuic_client_with_pinned_root_certificate_verifies_and_tunnels",
    ),
    (
        "native-tuic-tls-rejection",
        "tuic_v5",
        "ripdpi-tuic",
        "loopback_e2e",
        "tuic_client_with_unrelated_root_certificate_fails_verification",
    ),
    (
        "native-trojan-tcp",
        "trojan",
        "ripdpi-trojan",
        "tls_connect",
        "tls_client_sends_connect_request_and_pipes_payload_to_target",
    ),
    (
        "native-hysteria2-tcp",
        "hysteria2",
        "ripdpi-hysteria2",
        "loopback_e2e",
        "hysteria2_client_round_trips_through_loopback_after_auth",
    ),
    (
        "native-masque-udp",
        "masque",
        "ripdpi-masque",
        "lib",
        "tests::h3_connect_udp_honors_root_certificate_and_echoes_context_zero_datagrams",
    ),
    (
        "native-masque-tls-rejection",
        "masque",
        "ripdpi-masque",
        "lib",
        "tests::connect_over_h2_with_unrelated_root_certificate_fails_verification",
    ),
    (
        "native-naiveproxy-tcp",
        "naiveproxy",
        "ripdpi-naiveproxy",
        "bin",
        "tests::socks5_client_round_trip_over_h2_naive_padding_fixture",
    ),
    (
        "native-naiveproxy-recovery",
        "naiveproxy",
        "ripdpi-naiveproxy",
        "bin",
        "tests::helper_reconnects_after_upstream_h2_stream_failure",
    ),
    (
        "native-shadowtls-tcp",
        "shadowtls_v3",
        "ripdpi-relay-core",
        "lib",
        "tests::backend_fixture_tests::relay_runtime_builds_shadowtls_backend_with_inner_vless_profile",
    ),
    (
        "native-shadowsocks-tcp",
        "shadowsocks",
        "ripdpi-relay-core",
        "lib",
        "tests::backend_fixture_tests::relay_runtime_builds_shadowsocks_backend_and_connects_tcp_fixture",
    ),
    (
        "native-shadowsocks-udp",
        "shadowsocks",
        "ripdpi-relay-core",
        "lib",
        "tests::backend_fixture_tests::relay_runtime_builds_shadowsocks_udp_associate_fixture",
    ),
    (
        "native-trojan-udp",
        "trojan",
        "ripdpi-relay-core",
        "lib",
        "tests::backend_fixture_tests::relay_runtime_builds_trojan_udp_associate_fixture",
    ),
    (
        "native-vless-xudp",
        "vless_reality",
        "ripdpi-relay-core",
        "lib",
        "tests::backend_fixture_tests::relay_runtime_round_trips_udp_through_vless_reality_xudp",
    ),
    (
        "native-vless-xhttp",
        "vless",
        "ripdpi-relay-core",
        "lib",
        "tests::cross_stack::cross_stack_vless_over_xhttp_over_reality_single_stream",
    ),
    (
        "native-cloudflare-adapter",
        "cloudflare_tunnel",
        "ripdpi-relay-core",
        "lib",
        "tests::backend_fixture_tests::relay_runtime_routes_cloudflare_tunnel_through_xhttp_backend",
    ),
    (
        "native-tor-capability",
        "tor",
        "ripdpi-relay-core",
        "lib",
        "tests::backend_fixture_tests::tor_backend_builds_in_process_and_rejects_udp",
    ),
):
    native(identifier, protocol, package, target, test)
native(
    "native-chain-tcp",
    "chain_relay",
    "ripdpi-proxy-runtime",
    "network_e2e",
    "chained_upstream_round_trip_records_fixture_socks_usage_end_to_end",
    ignored=True,
)

UPSTREAM = {
    "ssh": (
        "https://github.com/enfein/mieru.git",
        "155ebbd60f86e472586a60d7ffe58ec8f8682cb1",
    ),
    "mieru": (
        "https://github.com/enfein/mieru.git",
        "155ebbd60f86e472586a60d7ffe58ec8f8682cb1",
    ),
    "anytls": (
        "https://github.com/anytls/anytls-go.git",
        "2012ef89768409f45437f1c06a7af5f6eea402ad",
    ),
}
for protocol, tests in {
    "ssh": {
        "password": "password_auth_exchanges_payload_with_upstream",
        "private-key": "encrypted_private_key_auth_exchanges_payload_with_upstream",
        "host-key-rejection": "changed_host_key_is_rejected_before_authentication",
    },
    "mieru": {
        "tcp": "tcp_stream_exchanges_payload_with_upstream",
        "multiplex": "multiplexed_tcp_streams_exchange_without_cross_contamination",
        "stop": "tests::backend_fixture_tests::mieru_off_socks_payload_and_stop_with_upstream",
    },
    "anytls": {
        "tcp": "tcp_stream_exchanges_payload_with_upstream",
        "udp": "udp_datagrams_exchange_with_upstream",
        "auth-rejection": "upstream_rejects_wrong_password",
    },
}.items():
    for purpose, test in tests.items():
        SCENARIOS[f"independent-{protocol}-{purpose}"] = {
            "protocol": protocol,
            "tier": "independent-peer",
            "driver": "outbound",
            "test": test,
            "checks": ["exact-test-executed", "pinned-peer-identity"],
            "peer_identity": {
                "repository": UPSTREAM[protocol][0],
                "revision": UPSTREAM[protocol][1],
            },
        }
        if protocol == "ssh":
            SCENARIOS[f"independent-{protocol}-{purpose}"]["peer_identity"].update(
                {
                    "implementation": "golang.org/x/crypto/ssh",
                    "module_version": "v0.33.0",
                    "module_sum": "h1:IOBPskki6Lysi0lo9qQvbxiQ+FvsCC/YWOecCHAixus=",
                    "dependency_manifest": "go.mod/go.sum from the pinned Mieru checkout",
                }
            )

SCENARIOS["independent-awg-tcp-udp"] = {
    "protocol": "amneziawg",
    "tier": "independent-peer",
    "driver": "awg",
    "test": "standalone_profile_exchanges_tcp_and_udp_with_independent_awg_peer",
    "checks": ["exact-test-executed", "pinned-peer-identity"],
    "peer_identity": {
        "implementation": "amneziawg-go",
        "version": "v0.2.18",
        "revision": "f4f4c999267437c3eb909e8d0e5278fb4596d9a7",
    },
}
for purpose, test in {
    "tcp-udp": "independent_peer_exchanges_tcp_and_udp",
    "auth-rejection": "independent_peer_rejects_wrong_auth",
    "tls-rejection": "independent_peer_rejects_untrusted_certificate",
}.items():
    SCENARIOS[f"independent-hysteria2-{purpose}"] = {
        "protocol": "hysteria2",
        "tier": "independent-peer",
        "driver": "hysteria2",
        "test": test,
        "checks": [
            "exact-test-executed",
            "pinned-peer-identity",
            "destination-receipts",
        ],
        "peer_identity": {
            "implementation": "HyNetworks/hysteria",
            "version": "app/v2.9.0",
        },
        "tls_scope": "Explicit insecure mode for local payload/auth; separate default secure rejection. No trusted-CA acceptance claim.",
    }

EXTERNAL_BOUNDARIES = {
    "cloudflare_tunnel": "Local adapter checks do not test the Cloudflare network.",
    "warp": "Local AWG or MASQUE does not test Cloudflare registration or service.",
    "tor": "Local capability checks do not test public Tor or Snowflake connectivity.",
    "apps_script": "Local transport checks do not test the Google Apps Script service.",
}
