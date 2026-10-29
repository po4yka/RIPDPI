"""Semantic negatives use independently counted, small UDP exchanges."""
import copy
import unittest

from scripts.tests.awg_wire_validation import validate_wire_observation


def small_cpa_observation():
    # Four H1 messages (148 auth bytes each), four empty transports (48),
    # and eight IPv4+UDP+17-byte payload transports (93), with S4 excluded.
    return {
        "schema_version": 2, "wire_profile": "v3-cpa-trailers", "padding": [12, 20, 16, 24],
        "headers_in_clear": 0, "handshakes": 4, "fresh_signature_packets": 4,
        "signature_order": "I1-I5,Jc,initiation", "signature_tags": ["b", "r", "rd", "rc", "t"],
        "evidence": {
            "lengths": [[{"200": 4}, None, None, {"72": 4, "117": 8}],
                        [None, {"130": 4}, None, {"117": 8}]],
            "authenticated_keepalives": 4, "ip_lengths": {"45": 8},
            "peer_counters": {"rx_bytes": 1528, "tx_bytes": 1368, "last_handshake_time_sec": 1, "last_handshake_time_nsec": 0},
        },
    }


def account_transport(observation):
    # Keep the counter internally consistent after a wire mutation: the
    # negative must fail on semantics, not a stale checksum or byte count.
    observation["evidence"]["peer_counters"]["rx_bytes"] = 592 + sum(
        (int(length)-24)*count for length, count in observation["evidence"]["lengths"][0][3].items())


class WireSemantics(unittest.TestCase):
    def validate(self, observation):
        validate_wire_observation(observation, "v3-cpa-trailers", 1280, 4, "small-datagrams")

    def test_small_data_and_authenticated_empty_have_independent_lengths(self):
        self.validate(small_cpa_observation())

    def test_each_required_message_type_must_be_present(self):
        for side, kind in ((0, 0), (1, 1), (0, 3), (1, 3)):
            observation = small_cpa_observation()
            observation["evidence"]["lengths"][side][kind] = None
            with self.subTest(side=side, kind=kind), self.assertRaises(ValueError): self.validate(observation)

    def test_missing_or_changed_padding_fails_with_matching_auth_counters(self):
        for transport in ({"56": 4, "117": 8}, {"72": 4, "101": 8}, {"72": 4, "133": 8}):
            observation = small_cpa_observation()
            observation["evidence"]["lengths"][0][3] = transport
            account_transport(observation)
            with self.subTest(transport=transport), self.assertRaises(ValueError): self.validate(observation)

    def test_missing_each_handshake_trailer_and_out_of_bound_cookie_fails(self):
        for side, kind, sizes in ((0, 0, {"160": 4}), (1, 1, {"112": 4}), (0, 2, {"500": 1})):
            observation = small_cpa_observation()
            observation["evidence"]["lengths"][side][kind] = sizes
            with self.subTest(kind=kind), self.assertRaises(ValueError): self.validate(observation)

    def test_missing_authentication_plaintext_or_fresh_prelude_fails(self):
        for mutation in (
            lambda o: o["evidence"].update(authenticated_keepalives=0),
            lambda o: o["evidence"].update(ip_lengths={"61": 8}),
            lambda o: o["evidence"]["peer_counters"].update(rx_bytes=0),
            lambda o: o.update(fresh_signature_packets=0),
            lambda o: o.update(headers_in_clear=1),
            lambda o: o.update(schema_version=1),
        ):
            observation = small_cpa_observation()
            mutation(observation)
            with self.assertRaises(ValueError): self.validate(observation)

    def test_missing_transport_rt_fails_with_valid_handshake_trailers(self):
        for side in (0, 1):
            observation = small_cpa_observation()
            observation.update(wire_profile="v3-trailers")
            observation["evidence"]["lengths"][0][3] = {"80": 4, "150": 8}
            observation["evidence"]["lengths"][1][3] = {"150": 8}
            observation["evidence"]["lengths"][side][3] = {"56": 4, "101": 8} if side == 0 else {"101": 8}
            account_transport(observation)
            with self.subTest(side=side), self.assertRaisesRegex(ValueError, "transport RT not observed"):
                validate_wire_observation(observation, "v3-trailers", 1280, 4, "small-datagrams")

    def test_alignment_and_random_padding_branches_use_different_bounds(self):
        for profile, inbound, outbound in (
            ("v3-aligned", {"56": 4, "104": 8}, {"104": 8}),
            ("v3-trailers", {"80": 4, "150": 8}, {"150": 8}),
        ):
            observation = small_cpa_observation()
            observation.update(wire_profile=profile)
            observation["evidence"]["lengths"][0][3] = inbound
            observation["evidence"]["lengths"][1][3] = outbound
            if profile == "v3-aligned":
                observation["evidence"]["lengths"][0][0] = {"160": 4}
                observation["evidence"]["lengths"][1][1] = {"112": 4}
            account_transport(observation)
            validate_wire_observation(observation, profile, 1280, 4, "small-datagrams")
            changed = copy.deepcopy(observation)
            changed["evidence"]["lengths"][0][3] = {"56": 4, "101": 8} if profile == "v3-aligned" else {"80": 4, "500": 8}
            account_transport(changed)
            with self.assertRaises(ValueError): validate_wire_observation(changed, profile, 1280, 4, "small-datagrams")


if __name__ == "__main__":
    unittest.main()
