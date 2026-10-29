package main

import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/chacha20"
)

// wireProfile is the configured AWG envelope the observer checks. The zero value is the legacy profile.
type wireProfile struct {
	name         string
	fixedHeaders bool
	padding      [4]int
	headerKey    []byte // nil: message headers travel in the clear
	trailers     bool   // RandomTrailers: handshake messages may be longer than S + size
	iface        string // UAPI obfuscation keys after private_key
	peer         string // UAPI keys after the peer public_key
}

const signaturePrograms = "h1=100-199\nh2=200-299\nh3=300-399\nh4=400-499\ni1=<b 0xf0f1><r 16><rd 8><rc 8><t>\ni2=<b 0xf2>\ni3=<b 0xf3>\ni4=<b 0xf4>\ni5=<b 0xf5>\n"

var legacyWireProfile = wireProfile{name: "awg-3.1-ranges-v1", padding: [4]int{8, 12, 0, 0},
	iface: "jc=4\njmin=64\njmax=96\ns1=8\ns2=12\n" + signaturePrograms}

// amneziaVPN5xKey is a public test fixture key, never a production secret.
var amneziaVPN5xKey = bytes.Repeat([]byte{3}, 32)

// amneziaVPN5xWireProfile mirrors an AmneziaVPN 5.x server: AWG 3.1 with S1-S4 = 12,
// header protection, content padding, random trailers and ranged timers.
var amneziaVPN5xWireProfile = wireProfile{name: "amneziavpn-5x", padding: [4]int{12, 12, 12, 12}, headerKey: amneziaVPN5xKey, trailers: true,
	iface: "jc=4\njmin=64\njmax=96\ns1=12\ns2=12\ns3=12\ns4=12\n" + signaturePrograms +
		"header_protection_key=" + hex.EncodeToString(amneziaVPN5xKey) + "\ncontent_padding_addition=10-100\nrekey_after_time=100-120\n" +
		"rekey_timeout=3-7\nreject_after_time=150-180\nkeepalive_timeout=5-15\nmax_handshake_attempts=15-20\nrandom_trailers=true\ndisable_cookies=true\n",
	peer: "protocol_version=1\npersistent_keepalive_interval=25-35\n"}

func selectWireProfile(name string) (wireProfile, error) {
	switch name {
	case "", legacyWireProfile.name:
		return legacyWireProfile, nil
	case amneziaVPN5xWireProfile.name:
		return amneziaVPN5xWireProfile, nil
	}
	if name == "v3-cpa" || name == "v3-trailers" || name == "v3-cpa-trailers" || name == "v3-aligned" || name == "v3-fixed-headers" {
		profile := wireProfile{name: name, padding: [4]int{12, 20, 16, 24}, headerKey: amneziaVPN5xKey,
			iface: "jc=4\njmin=64\njmax=96\ns1=12\ns2=20\ns3=16\ns4=24\n" + signaturePrograms}
		if name == "v3-aligned" {
			profile.headerKey = nil
		} else {
			profile.iface += "header_protection_key=" + hex.EncodeToString(profile.headerKey) + "\n"
		}
		if name != "v3-aligned" && name != "v3-trailers" {
			profile.iface += "content_padding_addition=16\n"
		}
		profile.trailers = name == "v3-trailers" || name == "v3-cpa-trailers" || name == "v3-fixed-headers"
		if profile.trailers {
			profile.iface += "random_trailers=true\n"
		}
		if name == "v3-fixed-headers" {
			profile.fixedHeaders = true
			profile.iface = strings.NewReplacer("100-199", "101", "200-299", "202", "300-399", "303", "400-499", "404").Replace(profile.iface)
		}
		return profile, nil
	}
	return wireProfile{}, fmt.Errorf("unknown RIPDPI_AWG_PROFILE %q", name)
}

// headerMask is the 4-byte type mask: IETF ChaCha20 keyed by the header key, nonce = first 12 datagram bytes.
func headerMask(key, packet []byte) uint32 {
	cipher, err := chacha20.NewUnauthenticatedCipher(key, packet[:chacha20.NonceSize])
	if err != nil {
		panic(err)
	}
	var mask [4]byte
	cipher.XORKeyStream(mask[:], mask[:])
	return binary.LittleEndian.Uint32(mask[:])
}

func inHeaderRange(index int, header uint32) bool {
	return header >= uint32((index+1)*100) && header <= uint32((index+1)*100+99)
}

func (p wireProfile) matchesHeader(index int, header uint32) bool {
	if p.fixedHeaders {
		return header == uint32((index+1)*101)
	}
	return inHeaderRange(index, header)
}

// Measurements come from the actual received and sent datagrams, before upstream decoding.
type wireObservation struct {
	mu       sync.Mutex
	profile  wireProfile
	evidence wireEvidence
	// Protected profile only: raw headers inside a configured range, handshake trailers, transport sizes.
	clearHeaders   int
	trailers       int
	transportSizes map[int]bool
	inbound        [4]map[uint32]bool
	outbound       [4]map[uint32]bool
	signatures     map[string]bool
	stage          int
	handshakes     int
	failure        string
}

func (o *wireObservation) observe(packet []byte, inbound bool) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.signatures == nil {
		o.signatures = make(map[string]bool)
		o.transportSizes = make(map[int]bool)
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
	wire := o.wire()
	var mask uint32
	if wire.headerKey != nil && len(packet) >= chacha20.NonceSize {
		mask = headerMask(wire.headerKey, packet)
	}
	for index, padding := range wire.padding {
		// Match the pinned peer's size checks before interpreting a header.
		// A transport counter occupies the same offset as the padded H1 header.
		size := padding + []int{148, 92, 64, 32}[index]
		grown := index < 3 && wire.trailers && len(packet) > size
		if (index < 3 && len(packet) != size && !grown) || (index == 3 && len(packet) < size) {
			continue
		}
		raw := binary.LittleEndian.Uint32(packet[padding : padding+4])
		header := raw ^ mask
		if !wire.matchesHeader(index, header) {
			continue
		}
		o.evidence.record(wire, packet, inbound, index)
		if wire.headerKey != nil && wire.matchesHeader(index, raw) {
			o.clearHeaders++
		}
		if inbound {
			o.inbound[index][header] = true
			if grown {
				o.trailers++
			}
			if index == 3 {
				o.transportSizes[len(packet)] = true
			}
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

func (o *wireObservation) wire() wireProfile {
	if o.profile.name == "" {
		return legacyWireProfile
	}
	return o.profile
}

func (o *wireObservation) finish(cycles int) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.failure != "" {
		return fmt.Errorf("wire observation: %s", o.failure)
	}
	minimumHeaders := 2
	if o.wire().fixedHeaders {
		minimumHeaders = 1
	}
	if o.handshakes < cycles || len(o.signatures) < cycles || len(o.inbound[0]) == 0 || len(o.outbound[1]) == 0 || len(o.inbound[3]) < minimumHeaders || len(o.outbound[3]) < minimumHeaders {
		return fmt.Errorf("incomplete wire observation: handshakes=%d fresh-signatures=%d H4-in=%d H4-out=%d", o.handshakes, len(o.signatures), len(o.inbound[3]), len(o.outbound[3]))
	}
	wire := o.wire()
	if wire.headerKey != nil && o.clearHeaders != 0 {
		return fmt.Errorf("wire observation: %d protected headers travelled in the clear", o.clearHeaders)
	}
	result := map[string]any{
		"schema_version": 2, "evidence": o.evidence, "padding": wire.padding,
		"wire_profile": wire.name, "handshakes": o.handshakes,
		"fresh_signature_packets": len(o.signatures), "h4_in_values": len(o.inbound[3]), "h4_out_values": len(o.outbound[3]),
		"signature_order": "I1-I5,Jc,initiation", "signature_tags": []string{"b", "r", "rd", "rc", "t"},
	}
	if wire.headerKey != nil {
		result["padding"] = wire.padding
		result["headers_in_clear"] = o.clearHeaders
		result["handshake_trailers"] = o.trailers
		result["inbound_transport_sizes"] = len(o.transportSizes)
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]any{"wire_observation": result})
}
