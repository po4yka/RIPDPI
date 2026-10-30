package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"os"
	"strings"
	"time"
)

type capturedUDP struct {
	at                      time.Time
	source, destination     uint16
	sourceIP, destinationIP [4]byte
	ipLength                int
	payload                 []byte
}
type captureSummary struct {
	LinkType    uint32 `json:"link_type"`
	Packets     int    `json:"packets"`
	Nanoseconds bool   `json:"nanoseconds"`
}

// readUDPCapture accepts bounded classic PCAP only. The caller supplies a UDP
// capture filter; unsupported link formats, fragments and truncation fail.
func readUDPCapture(reader io.Reader, visit func(capturedUDP) error) (captureSummary, error) {
	var summary captureSummary
	header := make([]byte, 24)
	if _, err := io.ReadFull(reader, header); err != nil {
		return summary, err
	}
	var order binary.ByteOrder
	switch string(header[:4]) {
	case "\xd4\xc3\xb2\xa1":
		order = binary.LittleEndian
	case "\xa1\xb2\xc3\xd4":
		order = binary.BigEndian
	case "\x4d\x3c\xb2\xa1":
		order = binary.LittleEndian
		summary.Nanoseconds = true
	case "\xa1\xb2\x3c\x4d":
		order = binary.BigEndian
		summary.Nanoseconds = true
	default:
		return summary, errors.New("unsupported capture magic")
	}
	if order.Uint16(header[4:6]) != 2 || order.Uint16(header[6:8]) != 4 || order.Uint32(header[16:20]) > 1<<20 {
		return summary, errors.New("unsupported PCAP version or snap length")
	}
	summary.LinkType = order.Uint32(header[20:24])
	var offset int
	switch summary.LinkType {
	case 1:
		offset = 14
	case 113:
		offset = 16
	case 276:
		offset = 20
	case 101:
		offset = 0
	default:
		return summary, errors.New("unsupported capture link type")
	}
	var previous time.Time
	for {
		record := make([]byte, 16)
		_, err := io.ReadFull(reader, record)
		if err == io.EOF {
			return summary, nil
		}
		if err != nil {
			return summary, err
		}
		size, original := order.Uint32(record[8:12]), order.Uint32(record[12:16])
		if size != original || size > 65599 || size < uint32(offset+28) || size > order.Uint32(header[16:20]) {
			return summary, errors.New("truncated or oversized captured packet")
		}
		packet := make([]byte, size)
		if _, err = io.ReadFull(reader, packet); err != nil {
			return summary, err
		}
		if summary.LinkType == 1 && binary.BigEndian.Uint16(packet[12:14]) != 0x0800 ||
			summary.LinkType == 113 && binary.BigEndian.Uint16(packet[14:16]) != 0x0800 ||
			summary.LinkType == 276 && binary.BigEndian.Uint16(packet[:2]) != 0x0800 {
			return summary, errors.New("capture is not IPv4")
		}
		ip := packet[offset:]
		ihl := int(ip[0]&15) * 4
		total := int(binary.BigEndian.Uint16(ip[2:4]))
		if ip[0]>>4 != 4 || ihl < 20 || ihl+8 > len(ip) || total < ihl+8 || total > len(ip) || ip[9] != 17 || binary.BigEndian.Uint16(ip[6:8])&0x3fff != 0 {
			return summary, errors.New("invalid captured IPv4 UDP packet")
		}
		udp := ip[ihl:total]
		if int(binary.BigEndian.Uint16(udp[4:6])) != len(udp) {
			return summary, errors.New("captured UDP length differs")
		}
		fraction := int64(order.Uint32(record[4:8]))
		if !summary.Nanoseconds {
			fraction *= 1000
		}
		if fraction >= int64(time.Second) {
			return summary, errors.New("invalid capture timestamp fraction")
		}
		at := time.Unix(int64(order.Uint32(record[:4])), fraction)
		if !previous.IsZero() && at.Before(previous) {
			return summary, errors.New("capture clock moved backwards")
		}
		previous = at
		value := capturedUDP{at: at, source: binary.BigEndian.Uint16(udp[:2]), destination: binary.BigEndian.Uint16(udp[2:4]), ipLength: total, payload: udp[8:]}
		copy(value.sourceIP[:], ip[12:16])
		copy(value.destinationIP[:], ip[16:20])
		summary.Packets++
		if summary.Packets > 4096 {
			return summary, errors.New("small capture packet bound exceeded")
		}
		if err = visit(value); err != nil {
			return summary, err
		}
	}
}

func observeKernelCapture(profile wireProfile, cycles int) error {
	scenario := os.Getenv("RIPDPI_AWG_SCENARIO")
	timed := strings.HasPrefix(scenario, "timer-")
	if scenario != "small-datagrams" && !timed {
		return errors.New("kernel semantic capture requires small datagrams or timers")
	}
	var relay *relayCaptureCheck
	if timed {
		file, err := os.Open(os.Getenv("RIPDPI_AWG_KERNEL_RELAY"))
		if err != nil {
			return err
		}
		defer file.Close()
		var report kernelRelayReport
		decoder := json.NewDecoder(io.LimitReader(file, 1<<20))
		decoder.DisallowUnknownFields()
		if err = decoder.Decode(&report); err != nil {
			return err
		}
		relay, err = newRelayCaptureCheck(report)
		if err != nil {
			return err
		}
	}
	wire, err := os.Open(os.Getenv("RIPDPI_AWG_WIRE_PCAP"))
	if err != nil {
		return err
	}
	defer wire.Close()
	inner, err := os.Open(os.Getenv("RIPDPI_AWG_INNER_PCAP"))
	if err != nil {
		return err
	}
	defer inner.Close()
	observer := wireObservation{profile: profile, scenario: scenario}
	wireSummary, err := readUDPCapture(wire, func(packet capturedUDP) error {
		if relay != nil && packet.source != 51820 && packet.destination != 51820 {
			_, err := relay.accept(packet, false)
			return err
		}
		if (packet.source == 51820) == (packet.destination == 51820) {
			return errors.New("wire capture endpoint differs")
		}
		dropped := false
		if relay != nil {
			var err error
			dropped, err = relay.accept(packet, true)
			if err != nil {
				return err
			}
		}
		observer.captureTime = &packet.at
		previous := len(observer.evidence.Events)
		observer.observe(packet.payload, packet.destination == 51820)
		if dropped {
			if len(observer.evidence.Events) != previous+1 || observer.evidence.Events[previous].Type != 2 || observer.evidence.Events[previous].Direction != "out" {
				return errors.New("relay dropped a packet other than a handshake response")
			}
			observer.evidence.Events[previous].Dropped = true
		}
		return nil
	})
	if err != nil {
		return err
	}
	if relay != nil {
		if err = relay.finish(); err != nil {
			return err
		}
		end := time.Unix(0, relay.report.EndNS)
		if observer.captureTime == nil || end.Before(*observer.captureTime) || end.Sub(*observer.captureTime) > 10*time.Second {
			return errors.New("relay observation window differs")
		}
		observer.captureTime = &end
	}
	incoming, outgoing := 0, 0
	innerSummary, err := readUDPCapture(inner, func(packet capturedUDP) error {
		client, server := [4]byte{10, 77, 0, 2}, [4]byte{10, 77, 0, 1}
		payload := bytes.Equal(packet.payload, []byte("awg-padding-check"))
		if scenario == "timer-reject" {
			payload = payload || bytes.Equal(packet.payload, []byte("awg-expired-probe")) || bytes.Equal(packet.payload, []byte("awg-recover-probe"))
		}
		if packet.ipLength != 45 || !payload {
			return errors.New("inner capture payload or IP length differs")
		}
		if packet.sourceIP == client && packet.destinationIP == server && (packet.destination == 41002 || packet.destination == 41003) {
			incoming++
			observer.acceptedIP([]int{packet.ipLength})
		} else if packet.sourceIP == server && packet.destinationIP == client && (packet.source == 41002 || packet.source == 41003) {
			outgoing++
		} else {
			return errors.New("inner capture endpoint differs")
		}
		return nil
	})
	if err != nil {
		return err
	}
	want := 2 * cycles
	if scenario == "timer-persistent" || scenario == "timer-retry-exhaust" {
		want = 0
	}
	if scenario == "timer-rekey" || scenario == "timer-reject" {
		want = 6
	}
	if incoming != want || outgoing != want {
		return errors.New("inner capture does not contain each request and echo")
	}
	var counters map[string]uint64
	if err = json.Unmarshal([]byte(os.Getenv("RIPDPI_AWG_KERNEL_COUNTERS")), &counters); err != nil {
		return err
	}
	for key := range counters {
		switch key {
		case "rx_bytes", "tx_bytes", "last_handshake_time_sec", "last_handshake_time_nsec":
		default:
			return errors.New("unexpected kernel counter field")
		}
	}
	if len(counters) != 4 || (counters["last_handshake_time_sec"] == 0 && scenario != "timer-retry-exhaust") || counters["tx_bytes"] == 0 {
		return errors.New("kernel counters missing")
	}
	transports := 0
	var expected uint64
	for index, lengths := range observer.evidence.Lengths[0] {
		for length, count := range lengths {
			switch index {
			case 0:
				expected += uint64(148 * count)
			case 1:
				expected += uint64(92 * count)
			case 3:
				expected += uint64((length - profile.padding[3]) * count)
				transports += count
			}
		}
	}
	if expected != counters["rx_bytes"] || transports < incoming || (!timed && transports-incoming != cycles) {
		return errors.New("kernel authenticated accounting differs from captures")
	}
	observer.evidence.AuthenticatedKeepalives = transports - incoming
	observer.evidence.PeerCounters = counters
	observer.kernelCapture = map[string]any{"wire": wireSummary, "inner": innerSummary, "accepted_datagrams": incoming, "echoed_datagrams": outgoing,
		"keepalive_evidence": "closed-kernel-byte-and-inner-IP-accounting"}
	if relay != nil {
		observer.kernelCapture["relay_actions"] = relay.report
	}
	return observer.finish(cycles)
}
