"""Semantic negatives use independently counted, small UDP exchanges."""
import copy
import unittest

from scripts.tests.awg_wire_validation import validate_wire_observation, validate_timer_observation


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


def timer_observation(scenario):
    # Independent times and wire lengths for the fixed CPA=16 profile.
    rows = [(0, "in", 1, 200, 0), (.01, "out", 2, 130, 0)]
    if scenario == "timer-retry-loss":
        rows += [(1, "in", 1, 200, 0), (1.01, "out", 2, 130, 0)]
    start = 1.02 if scenario == "timer-retry-loss" else .02
    rows += [(start, "in", 4, 72, 101)]
    if scenario == "timer-persistent":
        rows += [(t, "in", 4, 72, 101) for t in (1.02, 3.02, 4.02, 6.02)]
        elapsed = 6.22
    else:
        rows += [(start+.01, "in", 4, 117, 101), (start+.02, "in", 4, 117, 101),
                 (start+.03, "out", 4, 117, 1), (start+.04, "out", 4, 117, 1)]
        elapsed = start+.1
        if scenario == "timer-idle":
            rows += [(1.06, "in", 4, 72, 101), (2.06, "in", 4, 72, 101)]
            elapsed = 3.76
        elif scenario == "timer-rekey":
            rows += [(2.5, "in", 1, 200, 0), (2.51, "out", 2, 130, 0), (2.52, "in", 4, 72, 202)]
            rows += [(t, direction, 4, 117, receiver) for t, direction, receiver in
                     ((2.53,"in",202),(2.54,"in",202),(2.55,"out",2),(2.56,"out",2),
                      (2.83,"in",202),(2.84,"in",202),(2.85,"out",2),(2.86,"out",2))]
            elapsed = 2.96
    result = small_cpa_observation()
    result.update(scenario=scenario, elapsed_ns=int(elapsed*1e9))
    evidence = result["evidence"]
    evidence.update(events=[], events_dropped=0)
    for index, (time, direction, kind, length, receiver) in enumerate(rows, 1):
        event = dict(id=index, at_ns=int(time*1e9), direction=direction, type=kind, length=length)
        if receiver: event.update(receiver=receiver, counter=index)
        if scenario == "timer-retry-loss" and index == 2: event["dropped"] = True
        evidence["events"].append(event)
    recount_timer(result)
    return result


def recount_timer(observation):
    from collections import Counter
    evidence = observation["evidence"]
    events = evidence["events"]
    evidence["lengths"] = [[dict(Counter(str(e["length"]) for e in events if e["direction"] == side and e["type"] == kind))
                           for kind in range(1, 5)] for side in ("in", "out")]
    incoming = [e for e in events if e["direction"] == "in"]
    initiations = sum(e["type"] == 1 for e in incoming)
    evidence["authenticated_keepalives"] = sum(e["type"] == 4 and e["length"] == 72 for e in incoming)
    ip_count = sum(e["type"] == 4 and e["length"] == 117 for e in incoming)
    evidence["ip_lengths"] = {"45": ip_count} if ip_count else {}
    evidence["peer_counters"]["rx_bytes"] = initiations*148 + sum(e["length"]-24 for e in incoming if e["type"] == 4)
    observation.update(handshakes=initiations, fresh_signature_packets=initiations)
    for index, event in enumerate(events, 1): event["id"] = index


class TimerSemantics(unittest.TestCase):
    def test_independent_timer_examples_and_missing_features(self):
        for scenario in ("timer-persistent", "timer-idle", "timer-retry-loss", "timer-rekey"):
            good = timer_observation(scenario)
            validate_timer_observation(good, 1280, scenario)
            bad = copy.deepcopy(good)
            events = bad["evidence"]["events"]
            if scenario in ("timer-persistent", "timer-idle"):
                # Removing the periodic/idle behavior keeps histograms and auth
                # counters internally consistent; the timing oracle must fail.
                events[:] = [e for e in events if not (e["type"] == 4 and e["length"] == 72 and e["at_ns"] > 500_000_000)]
                recount_timer(bad)
            elif scenario == "timer-retry-loss":
                events[1].pop("dropped")
            else:
                for event in events:
                    if event.get("receiver") == 202: event["receiver"] = 101
            with self.subTest(scenario=scenario), self.assertRaises(ValueError):
                validate_timer_observation(bad, 1280, scenario)

    def test_exhaustion_requires_attempt_limit_and_silence(self):
        good = timer_observation("timer-retry-loss")
        good.update(scenario="timer-retry-exhaust", elapsed_ns=7_500_000_000)
        events = good["evidence"]["events"]
        events[:] = events[:4] + [dict(id=5, at_ns=2_000_000_000, direction="in", type=1, length=200),
                                 dict(id=6, at_ns=2_010_000_000, direction="out", type=2, length=130, dropped=True)]
        events[3]["dropped"] = True
        good["evidence"]["peer_counters"]["last_handshake_time_sec"] = 0
        recount_timer(good)
        validate_timer_observation(good, 1280, "timer-retry-exhaust")
        boundary = copy.deepcopy(good)
        for event, at in zip(boundary["evidence"]["events"][2:], (1.75, 1.76, 3.5, 3.51)):
            event["at_ns"] = int(at*1e9)
        validate_timer_observation(boundary, 1280, "timer-retry-exhaust")
        for mutation in (
            lambda o: o.update(elapsed_ns=3_200_000_000),
            lambda o: o["evidence"]["events"].pop(),
            lambda o: o["evidence"]["events"][3].update(dropped=False),
            lambda o: o["evidence"]["peer_counters"].update(last_handshake_time_sec=1),
        ):
            bad = copy.deepcopy(good)
            mutation(bad)
            recount_timer(bad)
            with self.assertRaises(ValueError): validate_timer_observation(bad, 1280, "timer-retry-exhaust")

    def test_expiry_requires_stimulus_loss_old_key_rejection_and_recovery(self):
        good = timer_observation("timer-rekey")
        good.update(scenario="timer-reject", elapsed_ns=8_900_000_000, expired_probe=dict(no_reply=True, wait_ms=1000))
        events = good["evidence"]["events"]
        initial = events[:7]
        recovery = events[7:]
        for event in recovery: event["at_ns"] += 5_700_000_000
        drops = []
        for time in (4.6, 5.8, 7.0):
            drops += [dict(at_ns=int(time*1e9), direction="in", type=1, length=200),
                      dict(at_ns=int((time+.01)*1e9), direction="out", type=2, length=130, dropped=True)]
        events[:] = initial + drops + recovery
        recount_timer(good)
        validate_timer_observation(good, 1280, "timer-reject")
        for mutation in (
            lambda o: o.pop("expired_probe"),
            lambda o: o["expired_probe"].update(no_reply=False),
            lambda o: o["evidence"]["events"][8].update(dropped=False),
            lambda o: o["evidence"]["events"][-3].update(receiver=101),
        ):
            bad = copy.deepcopy(good)
            mutation(bad)
            with self.assertRaises(ValueError): validate_timer_observation(bad, 1280, "timer-reject")

    def test_retry_rejects_the_old_five_second_interval(self):
        bad = timer_observation("timer-retry-loss")
        for event in bad["evidence"]["events"][2:]: event["at_ns"] += 4_000_000_000
        bad["elapsed_ns"] += 4_000_000_000
        with self.assertRaisesRegex(ValueError, "retry interval"):
            validate_timer_observation(bad, 1280, "timer-retry-loss")

    def test_timer_windows_order_authentication_and_prelude_are_required(self):
        for mutation in (
            lambda o: o.update(elapsed_ns=1_000_000_000),
            lambda o: o["evidence"].update(events_dropped=1),
            lambda o: o["evidence"]["events"][3].update(at_ns=100_000_000),
            lambda o: o["evidence"]["events"][3].update(id=9),
            lambda o: o["evidence"].update(authenticated_keepalives=0),
            lambda o: o.update(fresh_signature_packets=0),
        ):
            observation = timer_observation("timer-persistent")
            mutation(observation)
            with self.assertRaises(ValueError): validate_timer_observation(observation, 1280, "timer-persistent")


if __name__ == "__main__":
    unittest.main()
