"""Semantic negatives use independently counted, small UDP exchanges."""
import copy
import unittest

from scripts.tests.awg_wire_validation import validate_wire_observation, validate_timer_observation, validate_cookie_observation, validate_negative_transport


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
            "peer_counters": {"rx_bytes": 1528, "tx_bytes": 1456, "last_handshake_time_sec": 1, "last_handshake_time_nsec": 0},
        },
    }


def account_transport(observation):
    # Keep the counter internally consistent after a wire mutation: the
    # negative must fail on semantics, not a stale checksum or byte count.
    observation["evidence"]["peer_counters"]["rx_bytes"] = 592 + sum(
        (int(length)-24)*count for length, count in observation["evidence"]["lengths"][0][3].items())
    observation["evidence"]["peer_counters"]["tx_bytes"] = sum(
        int(length)*count for values in observation["evidence"]["lengths"][1] if values for length,count in values.items())


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

    def test_outgoing_small_payload_count_and_padding_are_required(self):
        for sizes in ({"117":7},{"101":8}):
            bad=small_cpa_observation()
            bad["evidence"]["lengths"][1][3]=sizes
            with self.assertRaisesRegex(ValueError,"outgoing small-datagram"):
                self.validate(bad)

    def test_startup_keepalive_does_not_allow_missing_outgoing_capture_bytes(self):
        observation=small_cpa_observation()
        observation.update(wire_profile="amneziavpn-5x",padding=[12,12,12,12])
        evidence=observation["evidence"]
        evidence["lengths"][0][3]={"100":4,"110":8}
        evidence["lengths"][1][3]={"110":9}
        evidence["peer_counters"].update(rx_bytes=1728,tx_bytes=1510)
        validate_wire_observation(observation,"amneziavpn-5x",1280,4,"small-datagrams")
        evidence["lengths"][1][3]={"110":8}
        with self.assertRaisesRegex(ValueError,"peer tx accounting"):
            validate_wire_observation(observation,"amneziavpn-5x",1280,4,"small-datagrams")

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


def cookie_observation(disabled=False):
    from collections import Counter
    result = small_cpa_observation()
    result.update(scenario="cookies-disabled" if disabled else "cookies-enabled", elapsed_ns=4_000_000_000,
                  handshakes=1, fresh_signature_packets=1)
    events = []
    def event(time, direction, kind, length, receiver=0):
        item = dict(id=len(events)+1, at_ns=int(time*1e9), direction=direction, type=kind, length=length)
        if receiver: item.update(receiver=receiver, counter=len(events))
        events.append(item)
    event(0,"in",1,200);event(.01,"out",2,130);event(.02,"in",4,72,101);event(.1,"in",4,110,101)
    names = ["invalid-mac1","admission"] + (["exhausted-noise"] if disabled else ["bad-mac2","valid-mac2"]) + ["challenge-budget","recovery"]
    phases = []
    for index, name in enumerate(names):
        start = 1.2+index*.05 if name not in ("challenge-budget","recovery") else 2.5 if name=="challenge-budget" else 3.7
        count = {"invalid-mac1":4,"admission":11,"challenge-budget":21}.get(name,1)
        sent = [dict(sha256=str(index+1)*64,at_ns=int(start*1e9)+i*1000) for i in range(count)]
        if name=="admission": sent[9]["sha256"]="a"*64
        replies=[]
        for _ in range(2 if name=="admission" else int(name in ("challenge-budget","recovery"))):
            replies.append(dict(type=2,length=130,authenticated=False,at_ns=int((start+.005)*1e9)))
        challenges=0 if disabled else {"admission":1,"bad-mac2":1,"challenge-budget":10}.get(name,0)
        replies += [dict(type=3,length=100,authenticated=True,at_ns=int((start+.006)*1e9)) for _ in range(challenges)]
        if name=="recovery": event(start+.005,"in",2,130);event(start+.007,"out",4,72,2)
        event(start+.011,"out",4,100+len("FENCE:"+name),2 if name=="recovery" else 1)
        event(start+.019,"in",4,100+len("ACK:"+name),202 if name=="recovery" else 101)
        phases.append(dict(name=name,sent=sent,responses=replies,fence_ns=int((start+.01)*1e9),ack_ns=int((start+.03)*1e9)))
    event(3.74,"out",4,111,2)
    evidence=result["evidence"]
    evidence.update(events=events,events_dropped=0,authenticated_keepalives=1)
    evidence["lengths"]=[[dict(Counter(str(e["length"]) for e in events if e["direction"]==side and e["type"]==kind))
                          for kind in range(1,5)] for side in ("in","out")]
    evidence["ip_lengths"]=dict(Counter(str(e["length"]-72) for e in events if e["direction"]=="in" and e["type"]==4 and e["length"]>72))
    evidence["peer_counters"]["rx_bytes"]=240+sum(e["length"]-24 for e in events if e["direction"]=="in" and e["type"]==4)
    result["cookies"]=dict(phases=phases,admission_window_ns=250_000_000,recovery_confirmed=True)
    return result


class CookieSemantics(unittest.TestCase):
    def test_both_cookie_policies_require_budget_edge_and_authenticated_recovery(self):
        for disabled in (False,True):
            good=cookie_observation(disabled)
            validate_cookie_observation(good,1280,good["scenario"])
            for mutation in (
                lambda o: o["cookies"].update(recovery_confirmed=False),
                lambda o: o["cookies"].update(admission_window_ns=1_000_000_000),
                lambda o: o["cookies"]["phases"][1]["sent"][9].update(sha256=o["cookies"]["phases"][1]["sent"][0]["sha256"]),
                lambda o: o["cookies"]["phases"][1]["responses"].pop(0),
                lambda o: o["evidence"]["events"][-2].update(receiver=101),
            ):
                bad=copy.deepcopy(good);mutation(bad)
                with self.assertRaises(ValueError):validate_cookie_observation(bad,1280,bad["scenario"])

    def test_missing_auth_bad_mac1_mac2_and_excess_challenges_fail(self):
        good=cookie_observation()
        for phase_index, change in ((0,"add"),(1,"auth"),(2,"remove"),(3,"add"),(4,"add")):
            bad=copy.deepcopy(good)
            phase=bad["cookies"]["phases"][phase_index]
            if change=="auth": phase["responses"][-1]["authenticated"]=False
            elif change=="remove": phase["responses"]=[]
            else: phase["responses"].append(dict(type=3,length=100,authenticated=True,at_ns=phase["fence_ns"]))
            with self.subTest(phase=phase_index), self.assertRaises(ValueError):
                validate_cookie_observation(bad,1280,bad["scenario"])
        bad=cookie_observation(True)
        phase=bad["cookies"]["phases"][2]
        phase["responses"].append(dict(type=2,length=130,authenticated=False,at_ns=phase["fence_ns"]))
        with self.assertRaises(ValueError):validate_cookie_observation(bad,1280,bad["scenario"])

    def test_missing_cookie_or_response_trailers_fail(self):
        for kind, base in ((2,112),(3,80)):
            bad=cookie_observation()
            for phase in bad["cookies"]["phases"]:
                for response in phase["responses"]:
                    if response["type"]==kind: response["length"]=base
            with self.assertRaisesRegex(ValueError,"RT not observed"):
                validate_cookie_observation(bad,1280,bad["scenario"])

    def test_unaccounted_journal_packet_cannot_supply_a_fence(self):
        good=cookie_observation()
        bad=copy.deepcopy(good)
        event=copy.deepcopy(bad["evidence"]["events"][-1])
        event.update(id=event["id"]+1,at_ns=3_750_000_000)
        bad["evidence"]["events"].append(event)
        with self.assertRaisesRegex(ValueError,"journal differs from histograms"):
            validate_cookie_observation(bad,1280,bad["scenario"])


def negative_transport_observation():
    from collections import Counter
    result=small_cpa_observation()
    result.update(scenario="negative-transport",elapsed_ns=1_000_000_000,handshakes=1,fresh_signature_packets=1)
    events=[]
    def event(time,direction,kind,length):
        item=dict(id=len(events)+1,at_ns=int(time*1e9),direction=direction,type=kind,length=length)
        if kind==4:item.update(receiver=101 if direction=="in" else 1,counter=len(events))
        events.append(item)
    event(0,"in",1,200);event(.01,"out",2,130);event(.02,"in",4,72);event(.1,"in",4,113)
    phases=[]
    modes=["corrupt","wrong-hp","foreign-source","replay","ambiguous","malformed-ip"]
    for index,mode in enumerate(modes):
        start=.2+index*.1
        changed=mode in ("corrupt","wrong-hp","ambiguous")
        count=2 if mode=="replay" else 1
        original=str(index+1)*64
        phase=dict(mode=mode,original_sha256=original,original_body_sha256="b"*64,
                   wire_sha256=["a"*64 if changed else original]*count,
                   wire_body_sha256=("c" if mode in ("corrupt","wrong-hp") else "b")*64,
                   original_cipher_sha256="d"*64,wire_cipher_sha256=("e" if mode=="corrupt" else "d")*64,
                   wire_ns=[int((start+i*.0001)*1e9) for i in range(count)],wire_length=356,
                   different_source=mode=="foreign-source",malformed_ip=mode=="malformed-ip",
                   fence_ns=int((start+.01)*1e9),ack_ns=int((start+.02)*1e9),restored_ns=0,confirmed_ns=0)
        if mode not in ("wrong-hp","ambiguous"):
            for index in range(count):event(start+.001+index*.001,"out",4,356)
        if mode=="ambiguous":phase["headers"]=[101,202,303,425]
        event(start+.011,"out",4,100+len("FENCE:"+mode));event(start+.019,"in",4,100+len("ACK:"+mode))
        if mode in modes[:3]:
            phase.update(restored_ns=int((start+.03)*1e9),confirmed_ns=int((start+.04)*1e9))
            event(start+.031,"out",4,356);event(start+.039,"in",4,100+len("CONFIRMED:"+mode))
        phases.append(phase)
    event(.9,"out",4,114)
    evidence=result["evidence"]
    evidence.update(events=events,events_dropped=0,authenticated_keepalives=1)
    evidence["lengths"]=[[dict(Counter(str(e["length"]) for e in events if e["direction"]==side and e["type"]==kind))
                         for kind in range(1,5)] for side in ("in","out")]
    evidence["ip_lengths"]=dict(Counter(str(e["length"]-72) for e in events if e["direction"]=="in" and e["type"]==4 and e["length"]>72))
    evidence["peer_counters"]["rx_bytes"]=148+sum(e["length"]-24 for e in events if e["direction"]=="in" and e["type"]==4)
    result["negative_transport"]=phases
    return result


class NegativeTransportSemantics(unittest.TestCase):
    def test_mutations_fences_and_original_packet_recovery_are_required(self):
        good=negative_transport_observation()
        validate_negative_transport(good,1280)
        mutations=(
            (0,lambda p:p.update(wire_sha256=[p["original_sha256"]])),
            (1,lambda p:p.update(wire_body_sha256=p["original_body_sha256"])),
            (1,lambda p:p.update(wire_cipher_sha256="e"*64)),
            (2,lambda p:p.update(different_source=False)),
            (3,lambda p:p.update(wire_sha256=p["wire_sha256"][:1])),
            (4,lambda p:p.update(headers=[101,202,303,1])),
            (4,lambda p:p.update(wire_body_sha256="c"*64)),
            (5,lambda p:p.update(malformed_ip=False)),
            (0,lambda p:p.update(restored_ns=0,confirmed_ns=0)),
            (1,lambda p:p.update(fence_ns=p["ack_ns"]+1)),
        )
        for index,mutation in mutations:
            bad=copy.deepcopy(good);mutation(bad["negative_transport"][index])
            with self.subTest(phase=index), self.assertRaises(ValueError):validate_negative_transport(bad,1280)


if __name__ == "__main__":
    unittest.main()


def kernel_response_observation(disabled=False):
    from collections import Counter
    result=small_cpa_observation()
    scenario='cookies-response-disabled' if disabled else 'cookies-response-enabled'
    result.update(scenario=scenario,elapsed_ns=5_000_000_000)
    events=[]
    def event(at,direction,kind,length):
        item=dict(at_ns=int(at*1e9),direction=direction,type=kind,length=length)
        if kind==4:item.update(receiver=101,counter=len(events))
        events.append(item)
    event(0,'in',1,200);event(.01,'out',2,130);event(.02,'in',4,72);event(.03,'in',4,110)
    names=['invalid-mac1','admission']+([] if disabled else ['bad-mac2','valid-mac2'])+['challenge-budget','recovery']
    phases=[]
    for index,name in enumerate(names):
        start={'challenge-budget':2.9,'recovery':4.1}.get(name,1.2+index*.1)
        count={'invalid-mac1':4,'admission':11,'bad-mac2':1,'valid-mac2':1,'challenge-budget':21,'recovery':0}[name]
        sent=[dict(sha256=('a' if name in ('admission','challenge-budget') else 'b')*64,at_ns=int((start+i*.0001)*1e9)) for i in range(count)]
        responses=[]
        for i in range(0 if disabled else {'admission':1,'bad-mac2':1,'challenge-budget':10}.get(name,0)):
            at=start+.01+i*.0001
            responses.append(dict(type=3,length=96,authenticated=True,at_ns=int(at*1e9),sha256=f'{index*100+i:064x}'))
            event(at,'in',3,96)
        fence,ack=start+.05,start+.06
        event(fence+.001,'out',4,100+len('FENCE:'+name));event(ack-.001,'in',4,100+len('ACK:'+name))
        phases.append(dict(name=name,sent=sent,responses=responses,fence_ns=int(fence*1e9),ack_ns=int(ack*1e9)))
    result['evidence'].update(events=events,events_dropped=0)
    recount_timer(result)
    result['evidence']['ip_lengths']=dict(Counter(str(n) for n in [38,*[28+len('ACK:'+name) for name in names]]))
    result['kernel_capture']=dict(relay_actions=dict(cookies_response=dict(original_sha256='a'*64,original_length=130,phases=phases)))
    return result


class KernelResponseCookies(unittest.TestCase):
    def test_response_policies_and_missing_feature_controls(self):
        from scripts.tests.awg_wire_validation import validate_kernel_cookie_response
        for disabled in (False,True):
            good=kernel_response_observation(disabled)
            validate_kernel_cookie_response(good,1280,good['scenario'])
            mutations=[lambda o:o['kernel_capture']['relay_actions']['cookies_response']['phases'].pop(),
                       lambda o:o['kernel_capture']['relay_actions']['cookies_response']['phases'][1]['sent'].pop(),
                       lambda o:o['kernel_capture']['relay_actions']['cookies_response']['phases'][0]['sent'][0].update(sha256='a'*64),
                       lambda o:o['kernel_capture']['relay_actions']['cookies_response']['phases'][1].update(ack_ns=9000000000),
                       lambda o:o['evidence'].update(authenticated_keepalives=0)]
            if not disabled:
                mutations += [lambda o:o['kernel_capture']['relay_actions']['cookies_response']['phases'][1]['responses'][0].update(authenticated=False),
                              lambda o:o['kernel_capture']['relay_actions']['cookies_response']['phases'][3]['responses'].append(dict(type=3)),
                              lambda o:o['kernel_capture']['relay_actions']['cookies_response']['phases'][-2]['responses'].pop()]
            for mutate in mutations:
                bad=copy.deepcopy(good);mutate(bad)
                with self.assertRaises(ValueError):validate_kernel_cookie_response(bad,1280,bad['scenario'])
