"""Independent constraints for versioned AWG peer wire observations."""
from collections import Counter

PROFILES = {
    "awg-3.1-ranges-v1": ([8, 12, 0, 0], False, False, None),
    "amneziavpn-5x": ([12, 12, 12, 12], True, True, (10, 100)),
    "v3-cpa": ([12, 20, 16, 24], True, False, (16, 16)),
    "v3-trailers": ([12, 20, 16, 24], True, True, None),
    "v3-cpa-trailers": ([12, 20, 16, 24], True, True, (16, 16)),
    "v3-aligned": ([12, 20, 16, 24], False, False, None),
    "v3-fixed-headers": ([12, 20, 16, 24], True, True, (16, 16)),
}


def require(condition, message):
    if not condition: raise ValueError(message)


def histogram(value, maximum):
    if value is None: return Counter()
    require(isinstance(value, dict), "missing length histogram")
    result = Counter()
    for size, count in value.items():
        require(isinstance(size, str) and size.isascii() and size.isdecimal(), "invalid length key")
        require(0 <= int(size) <= maximum and type(count) is int and 0 < count <= 2_000_000, "invalid histogram bound or count")
        result[int(size)] += count
    return result


def validate_wire_observation(observation, profile, mtu, cycles, scenario="payload"):
    require(type(cycles) is int and 1 <= cycles <= 4, "invalid session count")
    require(profile in PROFILES and mtu in (1280, 1420), "unknown wire profile or MTU")
    padding, protected, trailers, cpa = PROFILES[profile]
    require(observation.get("schema_version") == 2, "wire observation schema is obsolete")
    require(observation.get("wire_profile") == profile and observation.get("padding") == padding, "wire profile differs")
    require(observation.get("handshakes", 0) >= cycles and observation.get("fresh_signature_packets", 0) >= cycles,
            "handshake or fresh prelude missing")
    require(observation.get("signature_order") == "I1-I5,Jc,initiation" and observation.get("signature_tags") == ["b", "r", "rd", "rc", "t"],
            "signature/junk contract missing")
    if protected: require(observation.get("headers_in_clear") == 0, "protected header exposed")
    evidence = observation.get("evidence")
    require(isinstance(evidence, dict), "authenticated evidence missing")
    raw = evidence.get("lengths")
    require(isinstance(raw, list) and len(raw) == 2 and all(isinstance(side, list) and len(side) == 4 for side in raw),
            "packet type histograms missing")
    lengths = [[histogram(value, mtu + max(padding) + 148) for value in side] for side in raw]
    for side in lengths:
        for index, values in enumerate(side):
            base = padding[index] + [148, 92, 64, 32][index]
            ceiling = max(base, (500 if index == 2 else mtu + padding[3] + 32) - 1)
            for size in values:
                require(size >= base, "truncated message")
                if index < 3:
                    require((base <= size <= ceiling) if trailers else size == base, "handshake/cookie trailer bound")
                else:
                    require(size <= mtu + padding[3] + 32, "transport exceeds MTU framing bound")
                    if not trailers and cpa is None:
                        plain = size - base
                        require(plain % 16 == 0 or plain == mtu, "transport alignment missing")
    incoming, outgoing = lengths
    require(sum(incoming[0].values()) >= cycles and sum(outgoing[1].values()) >= cycles,
            "initiation or response missing")
    require((incoming[3] and (outgoing[3] or scenario == "timer-persistent")) or scenario == "timer-retry-exhaust", "bidirectional transport missing")
    if trailers:
        require(any(size > padding[0] + 148 for size in incoming[0]), "initiation trailers not observed")
        require(any(size > padding[1] + 92 for size in outgoing[1]), "response trailers not observed")
    ip_lengths = histogram(evidence.get("ip_lengths"), mtu)
    require((ip_lengths and min(ip_lengths) >= 20) or (not ip_lengths and scenario in ("timer-persistent", "timer-retry-exhaust")), "accepted IP payload missing")
    keepalives = evidence.get("authenticated_keepalives")
    require(type(keepalives) is int and keepalives >= (0 if scenario == "timer-retry-exhaust" else cycles), "authenticated keepalive missing")
    require(sum(incoming[3].values()) == sum(ip_lengths.values()) + keepalives,
            "wire transport differs from authenticated outcomes")
    counters = evidence.get("peer_counters")
    require(isinstance(counters, dict) and all(type(counters.get(key)) is int for key in
            ("rx_bytes", "tx_bytes", "last_handshake_time_sec", "last_handshake_time_nsec")), "peer counters missing")
    expected_rx = 148 * sum(incoming[0].values()) + 92 * sum(incoming[1].values())
    expected_rx += sum((size-padding[3])*count for size, count in incoming[3].items())
    require(counters["rx_bytes"] == expected_rx and (counters["last_handshake_time_sec"] > 0 or scenario == "timer-retry-exhaust") and counters["tx_bytes"] > 0,
            "authenticated byte accounting or key confirmation missing")
    if cpa and cpa[0] == cpa[1] and cpa[0] < 20:
        require(incoming[3][padding[3]+32+cpa[0]] >= keepalives, "empty transport CPA missing")
    require(scenario in ("payload", "small-datagrams", *TIMER_SCENARIOS), "unknown observation scenario")
    if scenario == "small-datagrams":
        require(ip_lengths == {45: 2*cycles} and keepalives == cycles, "small-datagram plaintext oracle differs")
        framing = padding[3]+32
        if cpa:
            # Initial window 500, IP length 45: every configured CPA byte fits.
            ranges = [(framing+cpa[0], framing+cpa[1])]*cycles
            ranges += [(framing+45+cpa[0], framing+45+cpa[1])]*(2*cycles)
        elif trailers:
            ranges = [(framing, 499)]*cycles + [(framing+45, 499)]*(2*cycles)
        else:
            ranges = [(framing, framing)]*cycles + [(framing+48, framing+48)]*(2*cycles)
        if trailers and cpa is None:
            # An all-zero sample is not feature evidence, even though each
            # individual zero is legal. Keep the failed receipt and rerun it.
            minimum = framing*cycles + (framing+45)*2*cycles
            require(sum(size*count for size, count in incoming[3].items()) > minimum,
                    "incoming transport RT not observed")
            require(sum(outgoing[3].values()) == 2*cycles and
                    all(size >= framing+45 for size in outgoing[3]) and
                    any(size > framing+45 for size in outgoing[3]),
                    "outgoing transport RT not observed")
        sizes = sorted(incoming[3].elements())
        require(len(sizes) == len(ranges) and all(low <= size <= high for size, (low, high) in zip(sizes, sorted(ranges))),
                "data/empty padding or CPA precedence differs")
    return evidence


TIMER_SCENARIOS = ("timer-persistent", "timer-idle", "timer-retry-loss", "timer-retry-exhaust", "timer-rekey", "timer-reject")


def validate_timer_observation(observation, mtu, scenario):
    require(scenario in TIMER_SCENARIOS and observation.get("scenario") == scenario, "timer scenario differs")
    evidence = validate_wire_observation(observation, "v3-cpa-trailers", mtu, 1, scenario)
    events = evidence.get("events")
    elapsed = observation.get("elapsed_ns")
    require(type(elapsed) is int and 0 < elapsed < 20_000_000_000, "missing bounded observation window")
    require(isinstance(events, list) and events and len(events) <= 1024 and evidence.get("events_dropped") == 0,
            "timer journal is missing or truncated")
    counts = [[Counter() for _ in range(4)] for _ in range(2)]
    previous = -1
    for index, event in enumerate(events, 1):
        require(event.get("id") == index and type(event.get("at_ns")) is int and previous <= event["at_ns"] <= elapsed,
                "timer journal order differs")
        previous = event["at_ns"]
        require(event.get("direction") in ("in", "out") and type(event.get("type")) is int and 1 <= event["type"] <= 4,
                "timer journal type differs")
        counts[event["direction"] == "out"][event["type"]-1][event["length"]] += 1
        if event["type"] == 4:
            require(type(event.get("receiver")) is int and event["receiver"] > 0 and
                    type(event.get("counter", 0)) is int and event.get("counter", 0) >= 0, "missing transport key/counter")
    require(counts == [[histogram(value, mtu+180) for value in side] for side in evidence["lengths"]], "journal differs from histograms")
    initiations = [e for e in events if e["direction"] == "in" and e["type"] == 1]
    responses = [e for e in events if e["direction"] == "out" and e["type"] == 2]
    incoming = [e for e in events if e["direction"] == "in" and e["type"] == 4]
    keepalives = [e for e in incoming if e["length"] == 72]
    data = [e for e in incoming if e["length"] == 117]
    require(len(incoming) == len(keepalives)+len(data) and len(keepalives) == evidence["authenticated_keepalives"],
            "timer transport does not match authenticated padding oracle")
    require(observation["fresh_signature_packets"] == len(initiations), "retry/rekey prelude is not fresh")
    require(all(not e.get("dropped") for e in events if e not in responses), "unexpected dropped traffic")
    if scenario == "timer-persistent":
        require(len(initiations) == len(responses) == 1 and not data and not evidence["ip_lengths"], "persistent timer sent data or rekey")
        require(4 <= len(keepalives) <= 7 and elapsed-keepalives[0]["at_ns"] >= 6_000_000_000, "persistent idle window incomplete")
        for first, second in zip(keepalives, keepalives[1:]):
            require(850_000_000 <= second["at_ns"]-first["at_ns"] <= 2_400_000_000, "persistent interval outside range")
        require(elapsed-keepalives[-1]["at_ns"] <= 2_400_000_000, "persistent timer stopped early")
    elif scenario == "timer-idle":
        replies = [e for e in events if e["direction"] == "out" and e["type"] == 4]
        require(len(initiations) == len(responses) == 1 and len(data) == len(replies) == 2 and len(keepalives) == 3, "idle keepalive count differs")
        delay = keepalives[1]["at_ns"] - replies[-1]["at_ns"]
        require(850_000_000 <= delay <= 1_400_000_000 and elapsed-replies[-1]["at_ns"] >= 3_500_000_000, "idle keepalive deadline/window differs")
        require(850_000_000 <= keepalives[2]["at_ns"]-keepalives[1]["at_ns"] <= 1_400_000_000,
                "second data event has no additional idle keepalive")
    elif scenario == "timer-retry-loss":
        # 1 s + <333 ms jitter + 250 ms poll, with 167 ms scheduling margin.
        require(len(initiations) == len(responses) == 2 and responses[0].get("dropped") is True and not responses[1].get("dropped"), "handshake loss/recovery missing")
        require(850_000_000 <= initiations[1]["at_ns"]-initiations[0]["at_ns"] <= 1_750_000_000, "retry interval differs")
        require(len(keepalives) == 1 and len(data) == 2 and incoming[0]["at_ns"] > responses[1]["at_ns"], "retry has no authenticated recovery")
    elif scenario == "timer-retry-exhaust":
        require(len(initiations) == len(responses) == 3 and all(e.get("dropped") is True for e in responses), "retry budget or loss stimulus differs")
        require(not incoming and not evidence["ip_lengths"] and evidence["peer_counters"]["last_handshake_time_sec"] == 0,
                "exhausted handshake accepted transport")
        for first, second in zip(initiations, initiations[1:]):
            require(850_000_000 <= second["at_ns"]-first["at_ns"] <= 1_750_000_000, "exhaustion retry interval differs")
        require(elapsed-initiations[-1]["at_ns"] >= 3_500_000_000, "post-exhaustion silence window missing")
    elif scenario == "timer-reject":
        probe = observation.get("expired_probe", {})
        require(probe.get("no_reply") is True and type(probe.get("wait_ms")) is int and 1000 <= probe["wait_ms"] <= 1500,
                "expired-key stimulus or rejection missing")
        require(len(initiations) == len(responses) and len(responses) >= 4 and
                not responses[0].get("dropped") and not responses[-1].get("dropped") and
                all(e.get("dropped") is True for e in responses[1:-1]), "expiry response-loss stimulus missing")
        require(responses[1]["at_ns"] >= 4_400_000_000 and responses[-1]["at_ns"] >= 8_000_000_000,
                "expiry/recovery boundary differs")
        old_key = incoming[0]["receiver"]
        require(not any(e["receiver"] == old_key and e["at_ns"] >= 4_000_000_000 for e in incoming),
                "expired key still sent transport")
        fresh = [e for e in data if e["receiver"] != old_key and e["at_ns"] > responses[-1]["at_ns"]]
        require(len(data) >= 4 and len(fresh) >= 2 and evidence["peer_counters"]["last_handshake_time_sec"] > 0,
                "expiry recovery has no accepted fresh-key payload")
    elif scenario == "timer-rekey":
        require(len(initiations) == len(responses) == 2 and not any(e.get("dropped") for e in responses), "rekey handshake missing")
        require(1_850_000_000 <= initiations[1]["at_ns"]-initiations[0]["at_ns"] <= 3_000_000_000, "rekey age differs")
        require(len(data) == 6 and len(keepalives) == 2 and keepalives[0]["receiver"] != keepalives[1]["receiver"], "new key confirmation missing")
        require(any(e["receiver"] == keepalives[1]["receiver"] and e["at_ns"] > keepalives[1]["at_ns"] for e in data), "new key has no accepted payload")
    if scenario not in ("timer-retry-loss", "timer-retry-exhaust", "timer-reject"):
        require(not any(e.get("dropped") for e in responses), "unexpected response loss")
    return evidence
