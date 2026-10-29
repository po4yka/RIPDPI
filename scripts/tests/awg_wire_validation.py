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
    require(incoming[3] and outgoing[3], "bidirectional transport missing")
    if trailers:
        require(any(size > padding[0] + 148 for size in incoming[0]), "initiation trailers not observed")
        require(any(size > padding[1] + 92 for size in outgoing[1]), "response trailers not observed")
    ip_lengths = histogram(evidence.get("ip_lengths"), mtu)
    require(ip_lengths and min(ip_lengths) >= 20, "accepted IP payload missing")
    keepalives = evidence.get("authenticated_keepalives")
    require(type(keepalives) is int and keepalives >= cycles, "authenticated keepalive missing")
    require(sum(incoming[3].values()) == sum(ip_lengths.values()) + keepalives,
            "wire transport differs from authenticated outcomes")
    counters = evidence.get("peer_counters")
    require(isinstance(counters, dict) and all(type(counters.get(key)) is int for key in
            ("rx_bytes", "tx_bytes", "last_handshake_time_sec", "last_handshake_time_nsec")), "peer counters missing")
    expected_rx = 148 * sum(incoming[0].values()) + 92 * sum(incoming[1].values())
    expected_rx += sum((size-padding[3])*count for size, count in incoming[3].items())
    require(counters["rx_bytes"] == expected_rx and counters["last_handshake_time_sec"] > 0 and counters["tx_bytes"] > 0,
            "authenticated byte accounting or key confirmation missing")
    if cpa and cpa[0] == cpa[1] and cpa[0] < 20:
        require(incoming[3][padding[3]+32+cpa[0]] >= keepalives, "empty transport CPA missing")
    require(scenario in ("payload", "small-datagrams"), "unknown observation scenario")
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
