package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"os"
	"sync"
	"time"
)

// Measurements come from the actual received and sent datagrams, before upstream decoding.
type wireObservation struct {
	mu         sync.Mutex
	inbound    [4]map[uint32]bool
	outbound   [4]map[uint32]bool
	signatures map[string]bool
	stage      int
	handshakes int
	failure    string
}

func (o *wireObservation) observe(packet []byte, inbound bool) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.signatures == nil {
		o.signatures = make(map[string]bool)
		for i := range o.inbound {
			o.inbound[i] = make(map[uint32]bool)
			o.outbound[i] = make(map[uint32]bool)
		}
	}
	if inbound {
		if len(packet) == 38 && bytes.Equal(packet[:2], []byte{0xf0, 0xf1}) {
			if o.stage != 0 {
				o.failure = "signature sequence restarted before initiation"
			}
			for _, b := range packet[18:26] {
				if b < '0' || b > '9' {
					o.failure = "invalid random digit"
				}
			}
			for _, b := range packet[26:34] {
				if !(b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z') {
					o.failure = "invalid random character"
				}
			}
			timestamp := int64(binary.BigEndian.Uint32(packet[34:38]))
			if difference := time.Now().Unix() - timestamp; difference < -60 || difference > 60 {
				o.failure = "invalid network-order timestamp"
			}
			o.signatures[string(packet[2:18])] = true
			o.stage = 1
			return
		}
		if o.stage >= 1 && o.stage <= 4 {
			if len(packet) != 1 || packet[0] != byte(0xf1+o.stage) {
				o.failure = "I1-I5 order mismatch"
			}
			o.stage++
			return
		}
		if o.stage >= 5 && o.stage <= 8 {
			if len(packet) < 64 || len(packet) > 96 {
				o.failure = "Jc junk must follow I1-I5"
			}
			o.stage++
			return
		}
	}
	for index, padding := range []int{8, 12, 0, 0} {
		if len(packet) < padding+4 {
			continue
		}
		header := binary.LittleEndian.Uint32(packet[padding : padding+4])
		if header < uint32((index+1)*100) || header > uint32((index+1)*100+99) {
			continue
		}
		if inbound {
			o.inbound[index][header] = true
			if index == 0 {
				if o.stage != 9 {
					o.failure = "initiation arrived without complete I/J prelude"
				}
				o.handshakes++
				o.stage = 0
			}
		} else {
			o.outbound[index][header] = true
		}
		return
	}
	if inbound {
		o.failure = "packet outside configured header ranges"
	}
}

func (o *wireObservation) finish(cycles int) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.failure != "" {
		return fmt.Errorf("wire observation: %s", o.failure)
	}
	if o.handshakes < cycles || len(o.signatures) < cycles || len(o.inbound[0]) == 0 || len(o.outbound[1]) == 0 || len(o.inbound[3]) < 2 || len(o.outbound[3]) < 2 {
		return fmt.Errorf("incomplete wire observation: handshakes=%d fresh-signatures=%d H4-in=%d H4-out=%d", o.handshakes, len(o.signatures), len(o.inbound[3]), len(o.outbound[3]))
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]any{"wire_observation": map[string]any{
		"wire_profile": "awg-3.1-ranges-v1", "handshakes": o.handshakes,
		"fresh_signature_packets": len(o.signatures), "h4_in_values": len(o.inbound[3]), "h4_out_values": len(o.outbound[3]),
		"signature_order": "I1-I5,Jc,initiation", "signature_tags": []string{"b", "r", "rd", "rc", "t"},
	}})
}
