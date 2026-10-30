package main

import (
	"encoding/json"
	"errors"
	"os"
	"time"
)

func observeKernelCookieCapture(profile wireProfile, scenario string) error {
	var report kernelRelayReport
	if err := readKernelJSON(os.Getenv("RIPDPI_AWG_KERNEL_RELAY"), &report); err != nil {
		return err
	}
	if report.Cookies == nil || report.StartNS <= 0 || report.EndNS <= report.StartNS {
		return errors.New("missing kernel cookie report")
	}
	wantPhases := 6
	if scenario == "cookies-response-disabled" {
		wantPhases = 4
	}
	if len(report.Cookies.Phases) != wantPhases {
		return errors.New("kernel cookie phase count differs")
	}
	check, err := newRelayCaptureCheck(report)
	if err != nil {
		return err
	}
	expectedOut, actualOut, cookies := map[string]int{}, map[string]int{}, map[string]int{}
	for _, action := range report.Actions {
		if action.Dropped {
			return errors.New("unexpected cookie relay drop")
		}
		if action.Direction == "out" {
			expectedOut[action.Digest]++
		}
	}
	for _, phase := range report.Cookies.Phases {
		for _, sent := range phase.Sent {
			expectedOut[sent.SHA256]++
		}
		for _, response := range phase.Responses {
			cookies[response.SHA256]++
		}
	}
	observer := wireObservation{profile: profile, scenario: scenario}
	observer.evidence.start = time.Unix(0, report.StartNS)
	wire, err := os.Open(os.Getenv("RIPDPI_AWG_WIRE_PCAP"))
	if err != nil {
		return err
	}
	defer wire.Close()
	var tx uint64
	original := 0
	var captured []capturedUDP
	wireSummary, err := readUDPCapture(wire, func(packet capturedUDP) error {
		captured = append(captured, packet)
		kernel := packet.source == 51820 || packet.destination == 51820
		if !kernel {
			if packet.destination == report.Port {
				_, err := check.accept(packet, false)
				return err
			}
			if packet.source != report.Port || packet.destination != check.clientPort || packet.sourceIP != [4]byte{127, 0, 0, 1} || packet.destinationIP != packet.sourceIP {
				return errors.New("cookie client endpoint differs")
			}
			actualOut[wireDigest(packet.payload)]++
			return nil
		}
		if _, err := check.accept(packet, true); err != nil {
			return err
		}
		if packet.source == 51820 {
			tx += uint64(len(packet.payload))
			if wireDigest(packet.payload) == report.Cookies.OriginalSHA256 {
				original++
				if len(packet.payload) != report.Cookies.OriginalLength {
					return errors.New("kernel response length differs")
				}
			}
		}
		observer.captureTime = &packet.at
		before := len(observer.evidence.Events)
		observer.observe(packet.payload, packet.destination == 51820)
		if len(observer.evidence.Events) > before && observer.evidence.Events[before].Type == 3 {
			digest := wireDigest(packet.payload)
			if cookies[digest] != 1 {
				return errors.New("unproved cookie authentication")
			}
			cookies[digest]--
		}
		return nil
	})
	if err != nil {
		return err
	}
	if err = checkKernelCookieMutations(profile, report, captured); err != nil {
		return err
	}
	if original != 1 || len(expectedOut) != len(actualOut) {
		return errors.New("kernel response or replay capture missing")
	}
	for digest, count := range expectedOut {
		if actualOut[digest] != count {
			return errors.New("cookie replay capture differs")
		}
	}
	for _, count := range cookies {
		if count != 0 {
			return errors.New("cookie response capture missing")
		}
	}
	if check.seen[0][0] != len(check.expected[0][0]) || check.seen[0][1] != len(check.expected[0][1]) || check.seen[1][0] != len(check.expected[1][0]) {
		return errors.New("cookie relay capture incomplete")
	}
	end := time.Unix(0, report.EndNS)
	if observer.captureTime == nil || end.Before(*observer.captureTime) || end.Sub(*observer.captureTime) > time.Second {
		return errors.New("cookie capture window differs")
	}
	observer.captureTime = &end
	wantedIn, wantedOut := map[string]int{"ARM:cookie": 1}, map[string]int{"DONE:cookie": 1}
	for _, phase := range report.Cookies.Phases {
		wantedIn["ACK:"+phase.Name]++
		wantedOut["FENCE:"+phase.Name]++
	}
	inner, err := os.Open(os.Getenv("RIPDPI_AWG_INNER_PCAP"))
	if err != nil {
		return err
	}
	defer inner.Close()
	incoming, outgoing := 0, 0
	innerSummary, err := readUDPCapture(inner, func(packet capturedUDP) error {
		inbound := packet.sourceIP == [4]byte{10, 77, 0, 2} && packet.destinationIP == [4]byte{10, 77, 0, 1} && packet.destination == 41002
		outbound := packet.sourceIP == [4]byte{10, 77, 0, 1} && packet.destinationIP == [4]byte{10, 77, 0, 2} && packet.source == 41002
		if !inbound && !outbound {
			return errors.New("cookie inner endpoint differs")
		}
		wanted := wantedOut
		if inbound {
			wanted = wantedIn
			incoming++
			observer.acceptedIP([]int{packet.ipLength})
		} else {
			outgoing++
		}
		value := string(packet.payload)
		if wanted[value] != 1 {
			return errors.New("cookie inner marker or count differs")
		}
		wanted[value]--
		return nil
	})
	if err != nil {
		return err
	}
	for _, wanted := range []map[string]int{wantedIn, wantedOut} {
		for _, count := range wanted {
			if count != 0 {
				return errors.New("cookie inner marker missing")
			}
		}
	}
	var counters map[string]uint64
	if err := json.Unmarshal([]byte(os.Getenv("RIPDPI_AWG_KERNEL_COUNTERS")), &counters); err != nil {
		return err
	}
	var rx uint64
	transports := 0
	for index, lengths := range observer.evidence.Lengths[0] {
		for length, count := range lengths {
			switch index {
			case 0:
				rx += uint64(148 * count)
			case 1:
				rx += uint64(92 * count)
			case 3:
				rx += uint64((length - profile.padding[3]) * count)
				transports += count
			}
		}
	}
	if len(counters) != 4 || counters["rx_bytes"] != rx || counters["tx_bytes"] != tx || counters["last_handshake_time_sec"] == 0 || transports-incoming != 1 {
		return errors.New("cookie authenticated accounting differs")
	}
	observer.evidence.AuthenticatedKeepalives = 1
	observer.evidence.PeerCounters = counters
	observer.kernelCapture = map[string]any{"wire": wireSummary, "inner": innerSummary, "accepted_datagrams": incoming, "echoed_datagrams": outgoing, "keepalive_evidence": "closed-kernel-byte-and-inner-IP-accounting", "relay_actions": report}
	return observer.finish(1)
}
