package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/netip"
	"os"
	"time"
)

func observeNativeKernel(profile wireProfile, scenario string) error {
	var report kernelRelayReport
	if err := readKernelJSON(os.Getenv("RIPDPI_AWG_KERNEL_RELAY"), &report); err != nil {
		return err
	}
	if report.Native == nil {
		return errors.New("native report missing")
	}
	native := report.Native
	if report.Port == 0 || report.Port == 51820 || report.StartNS != native.StartNS || report.EndNS <= native.StartNS || report.EndNS-native.StartNS >= int64(20*time.Second) || len(native.Actions) == 0 || len(native.Actions) > 1024 {
		return errors.New("native observation bounds differ")
	}
	injectionPositions := map[int]bool{}
	injectionTimes := []int64{}
	expected := [4][]string{} // kernel in/out, client in/out
	for _, action := range native.Actions {
		switch action.Origin {
		case "kernel":
			if (action.SHA256 == native.A || action.SHA256 == native.C) && !action.Forward || action.SHA256 == native.B && action.Forward {
				return errors.New("native A/B/C forwarding differs")
			}
			expected[1] = append(expected[1], action.SHA256)
			if action.Forward {
				expected[3] = append(expected[3], action.SHA256)
			}
		case "client":
			expected[2] = append(expected[2], action.SHA256)
			if action.Forward {
				expected[0] = append(expected[0], action.SHA256)
			}
		case "inject":
			if !action.Forward {
				return errors.New("native injection not forwarded")
			}
			injectionPositions[len(expected[3])] = true
			expected[3] = append(expected[3], action.SHA256)
		default:
			return errors.New("native action origin")
		}
	}
	wire, err := os.Open(os.Getenv("RIPDPI_AWG_WIRE_PCAP"))
	if err != nil {
		return err
	}
	defer wire.Close()
	seen := [4]int{}
	client := uint16(0)
	var tx uint64
	var rx uint64
	var initiations [][]byte
	var replies [][]byte
	var cookie []byte
	observer := wireObservation{profile: profile}
	observer.evidence.start = time.Unix(0, native.StartNS)
	summary, err := readUDPCapture(wire, func(packet capturedUDP) error {
		if packet.sourceIP != [4]byte{127, 0, 0, 1} || packet.destinationIP != packet.sourceIP {
			return errors.New("native capture not loopback")
		}
		leg := 0
		switch {
		case packet.source == 51820 && packet.destination == report.Port:
			leg = 1
		case packet.destination == 51820 && packet.source == report.Port:
			leg = 0
		case packet.destination == report.Port:
			leg = 2
			if client == 0 {
				client = packet.source
			}
			if client != packet.source {
				return errors.New("native client changed")
			}
		case packet.source == report.Port && packet.destination == client:
			leg = 3
		default:
			return errors.New("native capture endpoint differs")
		}
		if seen[leg] >= len(expected[leg]) || expected[leg][seen[leg]] != wireDigest(packet.payload) {
			return errors.New("native relay capture order or bytes differ")
		}
		if leg == 3 && injectionPositions[seen[leg]] {
			injectionTimes = append(injectionTimes, packet.at.UnixNano()-native.StartNS)
		}
		if leg == 2 {
			kind, body := nativeType(profile, packet.payload)
			if kind == 2 && len(body) >= 12 && len(initiations) >= 2 {
				b, _ := cookieWireBody(profile, 1, 148, initiations[1])
				if binary.LittleEndian.Uint32(body[8:12]) == binary.LittleEndian.Uint32(b[4:8]) {
					return errors.New("fresh native B bypassed work budget")
				}
			}
		}
		seen[leg]++
		if leg > 1 {
			return nil
		}
		kind, body := nativeType(profile, packet.payload)
		observer.captureTime = &packet.at
		observer.observe(packet.payload, leg == 1)
		if leg == 1 {
			tx += uint64(len(packet.payload))
			if kind == 1 {
				initiations = append(initiations, packet.payload)
			}
		} else {
			switch kind {
			case 1:
				return errors.New("Rust initiated kernel session")
			case 2:
				rx += 92
				replies = append(replies, body)
			case 3:
				cookie = packet.payload
			case 4:
				rx += uint64(len(packet.payload) - profile.padding[3])
			}
		}
		return nil
	})
	if err != nil {
		return err
	}
	if observer.captureTime == nil || observer.captureTime.UnixNano() > report.EndNS {
		return errors.New("native capture exceeds observation window")
	}
	for leg, count := range seen {
		if count != len(expected[leg]) {
			return errors.New("native capture incomplete")
		}
	}
	if len(initiations) != 3 || len(replies) != 2 || observer.failure != "" || observer.handshakes != 3 || len(observer.signatures) != 3 || observer.clearHeaders != 0 {
		return errors.New("native handshake/prelude evidence differs")
	}
	a, err := cookieWireBody(profile, 1, 148, initiations[0])
	if err != nil {
		return err
	}
	b, _ := cookieWireBody(profile, 1, 148, initiations[1])
	c, _ := cookieWireBody(profile, 1, 148, initiations[2])
	ids := []uint32{binary.LittleEndian.Uint32(a[4:8]), binary.LittleEndian.Uint32(b[4:8]), binary.LittleEndian.Uint32(c[4:8])}
	if ids[0] == ids[1] || ids[0] == ids[2] || ids[1] == ids[2] || binary.LittleEndian.Uint32(replies[0][8:12]) != ids[0] || binary.LittleEndian.Uint32(replies[1][8:12]) != ids[2] {
		return errors.New("native key indices not fresh")
	}
	if wireDigest(initiations[0]) != native.A || wireDigest(initiations[1]) != native.B || wireDigest(initiations[2]) != native.C {
		return errors.New("native A/B/C origin differs")
	}
	injects := []string{}
	for _, action := range native.Actions {
		if action.Origin == "inject" {
			injects = append(injects, action.SHA256)
		}
	}
	if len(injects) != 11 {
		return errors.New("native load count differs")
	}
	for i, digest := range injects {
		wanted := native.A
		if i == 10 {
			wanted = native.B
		}
		if digest != wanted {
			return errors.New("native load bytes differ")
		}
	}
	verifier, _ := newKernelCookieExercise(profile, nil)
	for _, body := range [][]byte{a, b} {
		mac := append([]byte(nil), body...)
		verifier.generator.AddMacs(mac)
		if !bytes.Equal(mac[116:132], body[116:132]) {
			return errors.New("native MAC1 differs")
		}
	}
	disabled := scenario == "cookies-native-disabled"
	if disabled {
		if cookie != nil || native.Cookie != "" || native.MAC2 {
			return errors.New("disabled native cookie emitted challenge")
		}
	} else {
		if cookie == nil || wireDigest(cookie) != native.Cookie {
			return errors.New("native cookie missing")
		}
		verifier.body = b
		verifier.active = 0
		verifier.evidence.Phases = []kernelCookiePhase{{Name: "native"}}
		verifier.observe(cookie, true, netip.AddrPortFrom(netip.MustParseAddr("127.0.0.1"), client))
		if verifier.failure != nil || len(verifier.evidence.Phases[0].Responses) != 1 || !verifier.evidence.Phases[0].Responses[0].Authenticated {
			return errors.New("native captured cookie invalid")
		}
		mac := append([]byte(nil), c...)
		verifier.generator.AddMacs(mac)
		if !native.MAC2 || bytes.Equal(c[132:148], make([]byte, 16)) || !bytes.Equal(mac[132:148], c[132:148]) {
			return errors.New("native captured retry MAC2 differs")
		}
	}
	inner, err := os.Open(os.Getenv("RIPDPI_AWG_INNER_PCAP"))
	if err != nil {
		return err
	}
	defer inner.Close()
	wantedIn := map[string]int{"ACK:trigger": 1, "ARM:cookie": 1, "ACK:baseline": 1, "ACK:load": 1, "ACK:recovery": 1}
	wantedOut := map[string]int{"FENCE:baseline": 1, "TRIGGER:native": 1, "FENCE:load": 1, "FENCE:recovery": 1, "DONE:cookie": 1}
	markers := map[string]int64{}
	incoming := 0
	innerSummary, err := readUDPCapture(inner, func(packet capturedUDP) error {
		in := packet.sourceIP == [4]byte{10, 77, 0, 2} && packet.destinationIP == [4]byte{10, 77, 0, 1} && packet.destination == 41002
		out := packet.sourceIP == [4]byte{10, 77, 0, 1} && packet.destinationIP == [4]byte{10, 77, 0, 2} && packet.source == 41002
		if !in && !out {
			return errors.New("native inner endpoint")
		}
		wanted := wantedOut
		if in {
			wanted = wantedIn
			incoming++
		}
		value := string(packet.payload)
		if wanted[value] != 1 {
			return errors.New("native marker differs")
		}
		wanted[value]--
		markers[value] = packet.at.UnixNano()
		return nil
	})
	if err != nil {
		return err
	}
	for _, wanted := range []map[string]int{wantedIn, wantedOut} {
		for _, count := range wanted {
			if count != 0 {
				return errors.New("missing native marker")
			}
		}
	}
	var counters map[string]uint64
	if err = json.Unmarshal([]byte(os.Getenv("RIPDPI_AWG_KERNEL_COUNTERS")), &counters); err != nil {
		return err
	}
	if len(counters) != 4 || counters["rx_bytes"] != rx || counters["tx_bytes"] != tx || counters["last_handshake_time_sec"] == 0 {
		return errors.New("native authenticated accounting differs")
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]any{"wire_observation": map[string]any{"schema": "native_kernel_cookies_v1", "scenario": scenario, "native": native, "injection_capture_ns": injectionTimes, "indices": ids, "reply_senders": []uint32{binary.LittleEndian.Uint32(replies[0][4:8]), binary.LittleEndian.Uint32(replies[1][4:8])}, "events": observer.evidence.Events, "markers": markers, "counters": counters, "incoming_datagrams": incoming, "kernel_capture": map[string]any{"wire": summary, "inner": innerSummary}}})
}
