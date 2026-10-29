package main

import (
	"encoding/binary"
	"errors"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/chacha20"
)

type packetEvent struct {
	ID        int    `json:"id"`
	AtNS      int64  `json:"at_ns"`
	Direction string `json:"direction"`
	Type      int    `json:"type"`
	Length    int    `json:"length"`
	Receiver  uint32 `json:"receiver,omitempty"`
	Counter   uint64 `json:"counter,omitempty"`
}

type wireEvidence struct {
	start                   time.Time
	Lengths                 [2][4]map[int]int `json:"lengths"`
	Events                  []packetEvent     `json:"events"`
	EventsDropped           int               `json:"events_dropped"`
	AuthenticatedKeepalives int               `json:"authenticated_keepalives"`
	IPLengths               map[int]int       `json:"ip_lengths"`
	PeerCounters            map[string]uint64 `json:"peer_counters"`
	packets                 int
}

// record is called with the observation mutex held. The journal is bounded;
// histograms retain all packets when a bulk transfer exceeds the event limit.
func (e *wireEvidence) record(profile wireProfile, packet []byte, inbound bool, index int) {
	if e.start.IsZero() {
		e.start = time.Now()
	}
	side, direction := 1, "out"
	if inbound {
		side, direction = 0, "in"
	}
	if e.Lengths[side][index] == nil {
		e.Lengths[side][index] = make(map[int]int)
	}
	e.Lengths[side][index][len(packet)]++
	e.packets++
	if len(e.Events) == 1024 {
		e.EventsDropped++
		return
	}
	event := packetEvent{ID: e.packets, AtNS: time.Since(e.start).Nanoseconds(), Direction: direction, Type: index + 1, Length: len(packet)}
	if index == 3 {
		header := append([]byte(nil), packet[profile.padding[3]:profile.padding[3]+16]...)
		if profile.headerKey != nil {
			cipher, _ := chacha20.NewUnauthenticatedCipher(profile.headerKey, packet[:12])
			cipher.XORKeyStream(header, header)
		}
		event.Receiver = binary.LittleEndian.Uint32(header[4:8])
		event.Counter = binary.LittleEndian.Uint64(header[8:16])
	}
	e.Events = append(e.Events, event)
}

func (o *wireObservation) acceptedIP(lengths []int) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.evidence.IPLengths == nil {
		o.evidence.IPLengths = make(map[int]int)
	}
	for _, length := range lengths {
		o.evidence.IPLengths[length]++
	}
}

func (o *wireObservation) verbose(format string, _ ...any) {
	// This pinned upstream branch runs only after AEAD and replay acceptance.
	// Other log formats, arguments, keys and addresses never enter evidence.
	if format == "%v - Receiving keepalive packet" {
		o.mu.Lock()
		o.evidence.AuthenticatedKeepalives++
		o.mu.Unlock()
	}
}

func parsePeerCounters(text string) (map[string]uint64, error) {
	counters := make(map[string]uint64)
	for _, line := range strings.Split(text, "\n") {
		key, value, _ := strings.Cut(line, "=")
		switch key {
		case "rx_bytes", "tx_bytes", "last_handshake_time_sec", "last_handshake_time_nsec":
			parsed, err := strconv.ParseUint(value, 10, 64)
			if err != nil {
				return nil, errors.New("invalid peer counter")
			}
			if _, present := counters[key]; present {
				return nil, errors.New("expected one peer")
			}
			counters[key] = parsed
		}
	}
	if len(counters) != 4 {
		return nil, errors.New("missing authenticated peer counters")
	}
	return counters, nil
}

// finishCounters waits for observed valid input to leave the peer receive queue.
// This is a bounded processing barrier, not a delay used to hide a failed packet.
func (o *wireObservation) finishCounters(get func() (string, error)) error {
	deadline := time.NewTimer(2 * time.Second)
	defer deadline.Stop()
	tick := time.NewTicker(10 * time.Millisecond)
	defer tick.Stop()
	for {
		text, err := get()
		if err != nil {
			return err
		}
		counters, err := parsePeerCounters(text)
		if err != nil {
			return err
		}
		o.mu.Lock()
		var bytes uint64
		transports := 0
		for index, lengths := range o.evidence.Lengths[0] {
			for length, count := range lengths {
				switch index {
				case 0:
					bytes += uint64(148 * count)
				case 1:
					bytes += uint64(92 * count)
				case 3:
					bytes += uint64((length - o.wire().padding[3]) * count)
					transports += count
				}
			}
		}
		accepted := o.evidence.AuthenticatedKeepalives
		for _, count := range o.evidence.IPLengths {
			accepted += count
		}
		ready := counters["rx_bytes"] == bytes && accepted == transports
		if ready {
			o.evidence.PeerCounters = counters
		}
		o.mu.Unlock()
		if ready {
			return nil
		}
		select {
		case <-deadline.C:
			return errors.New("wire packets did not reach authenticated peer outcomes")
		case <-tick.C:
		}
	}
}
