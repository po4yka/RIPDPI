package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"os"
	"sort"
	"time"

	"golang.org/x/crypto/chacha20"
)

func readKernelJSON(path string, value any) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	decoder := json.NewDecoder(io.LimitReader(file, 1<<20))
	decoder.DisallowUnknownFields()
	return decoder.Decode(value)
}

type negativeExpectedPacket struct {
	at      time.Time
	digest  string
	foreign bool
}

func checkKernelMutation(mode string, original, wire []byte) error {
	if len(original) != 356 || len(wire) != 356 {
		return errors.New("kernel mutation length differs")
	}
	valid := false
	switch mode {
	case "corrupt":
		valid = bytes.Equal(original[:355], wire[:355]) && original[355]^wire[355] == 1
	case "wrong-hp":
		valid = bytes.Equal(original[:24], wire[:24]) && bytes.Equal(original[40:], wire[40:]) && !bytes.Equal(original[24:40], wire[24:40])
		clear := make([][]byte, 2)
		for i, value := range [][]byte{original, wire} {
			key := amneziaVPN5xKey
			if i == 1 {
				key = bytes.Repeat([]byte{4}, 32)
			}
			cipher, err := chacha20.NewUnauthenticatedCipher(key, value[:12])
			if err != nil {
				return err
			}
			clear[i] = make([]byte, 16)
			cipher.XORKeyStream(clear[i], value[24:40])
		}
		valid = valid && bytes.Equal(clear[0], clear[1])
	case "ambiguous":
		valid = bytes.Equal(original[:12], wire[:12]) && bytes.Equal(original[24:], wire[24:])
		mask := headerMask(amneziaVPN5xKey, wire)
		for i, offset := range []int{12, 20, 16} {
			valid = valid && binary.LittleEndian.Uint32(wire[offset:])^mask == uint32((i+1)*101)
		}
		valid = valid && inHeaderRange(3, binary.LittleEndian.Uint32(wire[24:])^mask)
	case "foreign-source", "replay", "malformed-ip":
		valid = bytes.Equal(original, wire)
	}
	if !valid {
		return errors.New("captured kernel mutation does not match its mode")
	}
	return nil
}

// Validate mutations against the actual original kernel ciphertext and both
// captured legs. Receipt digests alone cannot prove a packet was transformed.
func checkKernelNegativeWire(report kernelRelayReport, packets []capturedUDP) ([]capturedUDP, error) {
	if len(report.Negative) != 6 || report.StartNS <= 0 || report.EndNS <= report.StartNS || report.ForeignPort == 0 || report.ForeignPort == report.Port || report.ForeignPort == 51820 {
		return nil, errors.New("invalid negative relay report")
	}
	check, err := newRelayCaptureCheck(report)
	if err != nil {
		return nil, err
	}
	phases := make(map[string]*transportPhase)
	for index := range report.Negative {
		phase := &report.Negative[index]
		if phase.Mode != transportModes[index] || phases[phase.OriginalSHA256] != nil {
			return nil, errors.New("negative kernel phases differ")
		}
		phases[phase.OriginalSHA256] = phase
	}
	var front []capturedUDP
	var expected []negativeExpectedPacket
	var observedOriginals int
	originals := make(map[string][]byte)
	clientPort := uint16(0)
	for _, packet := range packets {
		kernel := packet.source == 51820 || packet.destination == 51820
		if kernel {
			if _, err = check.accept(packet, true); err != nil {
				return nil, err
			}
			if packet.source != 51820 {
				continue
			}
			digest := wireDigest(packet.payload)
			phase := phases[digest]
			if phase == nil {
				expected = append(expected, negativeExpectedPacket{packet.at, digest, false})
				continue
			}
			if len(packet.payload) != 356 || wireDigest(packet.payload[24:]) != phase.OriginalBodySHA256 || wireDigest(packet.payload[40:]) != phase.OriginalCipherSHA256 {
				return nil, errors.New("original kernel ciphertext differs")
			}
			observedOriginals++
			originals[digest] = packet.payload
			for _, wire := range phase.WireSHA256 {
				expected = append(expected, negativeExpectedPacket{packet.at, wire, phase.Mode == "foreign-source"})
			}
			if phase.RestoredNS != 0 {
				expected = append(expected, negativeExpectedPacket{time.Unix(0, report.StartNS+phase.RestoredNS), digest, false})
			}
			continue
		}
		if packet.sourceIP != [4]byte{127, 0, 0, 1} || packet.destinationIP != packet.sourceIP {
			return nil, errors.New("negative client capture is not loopback")
		}
		inbound := packet.destination == report.Port
		port := packet.destination
		if inbound {
			port = packet.source
			if _, err = check.accept(packet, false); err != nil {
				return nil, err
			}
		} else if packet.source != report.Port && packet.source != report.ForeignPort {
			return nil, errors.New("negative carrier source differs")
		}
		if clientPort == 0 {
			clientPort = port
		}
		if port == 0 || port != clientPort {
			return nil, errors.New("negative client endpoint changed")
		}
		front = append(front, packet)
	}
	if observedOriginals != 6 || check.seen[0][0] != len(check.expected[0][0]) || check.seen[0][1] != len(check.expected[0][1]) || check.seen[1][0] != len(check.expected[1][0]) {
		return nil, errors.New("negative kernel capture incomplete")
	}
	sort.SliceStable(expected, func(i, j int) bool { return expected[i].at.Before(expected[j].at) })
	index := 0
	for _, packet := range front {
		if packet.destination == report.Port {
			continue
		}
		if index >= len(expected) || wireDigest(packet.payload) != expected[index].digest || (packet.source == report.ForeignPort) != expected[index].foreign {
			return nil, errors.New("negative client mutation or recovery differs")
		}
		index++
		for _, phase := range report.Negative {
			for _, digest := range phase.WireSHA256 {
				if wireDigest(packet.payload) == digest {
					if err := checkKernelMutation(phase.Mode, originals[phase.OriginalSHA256], packet.payload); err != nil {
						return nil, err
					}
				}
				if wireDigest(packet.payload) == digest && (len(packet.payload) != 356 || wireDigest(packet.payload[24:]) != phase.WireBodySHA256 || wireDigest(packet.payload[40:]) != phase.WireCipherSHA256) {
					return nil, errors.New("captured mutation ciphertext differs")
				}
			}
		}
	}
	if index != len(expected) {
		return nil, errors.New("negative client capture incomplete")
	}
	return front, nil
}

func observeKernelNegativeCapture(profile wireProfile) error {
	var report kernelRelayReport
	if err := readKernelJSON(os.Getenv("RIPDPI_AWG_KERNEL_RELAY"), &report); err != nil {
		return err
	}
	wire, err := os.Open(os.Getenv("RIPDPI_AWG_WIRE_PCAP"))
	if err != nil {
		return err
	}
	defer wire.Close()
	var packets []capturedUDP
	wireSummary, err := readUDPCapture(wire, func(packet capturedUDP) error { packets = append(packets, packet); return nil })
	if err != nil {
		return err
	}
	front, err := checkKernelNegativeWire(report, packets)
	if err != nil {
		return err
	}
	observer := wireObservation{profile: profile, scenario: "negative-transport", kernelNegative: report.Negative}
	observer.evidence.start = time.Unix(0, report.StartNS)
	for _, packet := range front {
		skip := false
		if packet.destination != report.Port {
			for _, phase := range report.Negative {
				if phase.Mode == "wrong-hp" || phase.Mode == "ambiguous" {
					for _, digest := range phase.WireSHA256 {
						skip = skip || wireDigest(packet.payload) == digest
					}
				}
			}
		}
		if skip {
			continue
		}
		observer.captureTime = &packet.at
		observer.observe(packet.payload, packet.destination == report.Port)
	}
	end := time.Unix(0, report.EndNS)
	if len(packets) == 0 || end.Before(packets[len(packets)-1].at) || end.Sub(packets[len(packets)-1].at) > time.Second {
		return errors.New("negative capture window differs")
	}
	observer.captureTime = &end
	inner, err := os.Open(os.Getenv("RIPDPI_AWG_INNER_PCAP"))
	if err != nil {
		return err
	}
	defer inner.Close()
	var raw struct {
		IPLength int    `json:"ip_length"`
		Declared int    `json:"declared_ip_length"`
		Digest   string `json:"sha256"`
		AtNS     int64  `json:"at_unix_ns"`
		Port     int    `json:"destination_port"`
	}
	if err = readKernelJSON(os.Getenv("RIPDPI_AWG_KERNEL_RAW_RECEIPT"), &raw); err != nil {
		return err
	}
	malformed := 0
	incoming, outgoing := 0, 0
	wantedIn, wantedOut := map[string]int{"ARM:transport": 1}, map[string]int{"DONE:transport": 1}
	for _, mode := range transportModes {
		wantedIn["ACK:"+mode]++
		wantedOut[string(negativePayload(mode))]++
		wantedOut["FENCE:"+mode]++
		if mode == "corrupt" || mode == "wrong-hp" || mode == "foreign-source" {
			wantedIn["CONFIRMED:"+mode]++
		}
	}
	innerSummary, err := readUDPRecords(inner, true, func(packet capturedUDP) error {
		inbound := packet.sourceIP == [4]byte{10, 77, 0, 2} && packet.destinationIP == [4]byte{10, 77, 0, 1} && packet.destination == 41002
		outbound := packet.sourceIP == [4]byte{10, 77, 0, 1} && packet.destinationIP == [4]byte{10, 77, 0, 2} && packet.source == 41002
		if !inbound && !outbound {
			return errors.New("negative inner endpoint differs")
		}
		wanted := wantedOut
		if inbound {
			incoming++
			wanted = wantedIn
			observer.acceptedIP([]int{packet.ipLength})
		} else {
			outgoing++
		}
		value := string(packet.payload)
		if wanted[value] != 1 {
			return errors.New("negative inner payload or count differs")
		}
		wanted[value]--
		if packet.declaredIPLength != packet.ipLength {
			if !outbound || packet.declaredIPLength != 0 || packet.ipLength != 284 || !bytes.Equal(packet.payload, negativePayload("malformed-ip")) || raw.IPLength != 284 || raw.Declared != 0 || raw.Digest != wireDigest(packet.ip) || raw.Port != int(packet.destination) {
				return errors.New("raw malformed IP differs")
			}
			malformed++
		}
		return nil
	})
	if err != nil {
		return err
	}
	for _, wanted := range []map[string]int{wantedIn, wantedOut} {
		for _, count := range wanted {
			if count != 0 {
				return errors.New("missing negative inner packet")
			}
		}
	}
	if malformed != 1 {
		return errors.New("kernel did not encrypt the malformed IP stimulus")
	}
	var counters map[string]uint64
	if err = json.Unmarshal([]byte(os.Getenv("RIPDPI_AWG_KERNEL_COUNTERS")), &counters); err != nil {
		return err
	}
	if len(counters) != 4 || counters["last_handshake_time_sec"] == 0 {
		return errors.New("negative kernel counters missing")
	}
	var rx, tx uint64
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
	for _, packet := range packets {
		if packet.source == 51820 {
			tx += uint64(len(packet.payload))
		}
	}
	if counters["rx_bytes"] != rx || counters["tx_bytes"] != tx || transports-incoming != 1 {
		return errors.New("negative kernel authenticated accounting differs")
	}
	observer.evidence.AuthenticatedKeepalives = 1
	observer.evidence.PeerCounters = counters
	observer.kernelCapture = map[string]any{"wire": wireSummary, "inner": innerSummary, "accepted_datagrams": incoming, "echoed_datagrams": outgoing, "keepalive_evidence": "closed-kernel-byte-and-inner-IP-accounting", "relay_actions": report, "malformed_ip": raw}
	return observer.finish(1)
}
