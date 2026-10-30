package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"time"

	"golang.org/x/crypto/chacha20"
)

var transportModes = []string{"corrupt", "wrong-hp", "foreign-source", "replay", "ambiguous", "malformed-ip"}

type transportPhase struct {
	Mode                 string   `json:"mode"`
	OriginalSHA256       string   `json:"original_sha256"`
	OriginalBodySHA256   string   `json:"original_body_sha256"`
	OriginalCipherSHA256 string   `json:"original_cipher_sha256"`
	WireCipherSHA256     string   `json:"wire_cipher_sha256"`
	WireBodySHA256       string   `json:"wire_body_sha256"`
	WireSHA256           []string `json:"wire_sha256"`
	WireNS               []int64  `json:"wire_ns"`
	WireLength           int      `json:"wire_length"`
	Headers              []uint32 `json:"headers,omitempty"`
	DifferentSource      bool     `json:"different_source"`
	MalformedIP          bool     `json:"malformed_ip"`
	FenceNS              int64    `json:"fence_ns"`
	AckNS                int64    `json:"ack_ns"`
	RestoredNS           int64    `json:"restored_ns"`
	ConfirmedNS          int64    `json:"confirmed_ns"`
	original             []byte
	sent                 bool
	target               netip.AddrPort
}
type transportExercise struct {
	mu      sync.Mutex
	bind    *loopbackBind
	profile wireProfile
	start   time.Time
	phases  []*transportPhase
	active  *transportPhase
}

func newTransportExercise(bind *loopbackBind, profile wireProfile) *transportExercise {
	return &transportExercise{bind: bind, profile: profile, start: time.Now()}
}
func negativePayload(mode string) []byte {
	payload := bytes.Repeat([]byte{'x'}, 256)
	copy(payload, []byte("DATA:"+mode+":"))
	return payload
}
func packetDigest(packet []byte) string {
	sum := sha256.Sum256(packet)
	return hex.EncodeToString(sum[:])
}
func (t *transportExercise) mutateIP(bufs [][]byte, sizes []int, offset, count int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.active == nil || t.active.Mode != "malformed-ip" || t.active.MalformedIP {
		return
	}
	for i := 0; i < count; i++ {
		if sizes[i] != 284 || bufs[i][offset]>>4 != 4 {
			continue
		}
		// Keep routing addresses and actual buffer length; make the authenticated
		// IPv4 declared length invalid before the independent peer encrypts it.
		binary.BigEndian.PutUint16(bufs[i][offset+2:offset+4], 0)
		t.active.MalformedIP = true
		return
	}
}
func (t *transportExercise) send(packet []byte, socket *net.UDPConn, target netip.AddrPort) (bool, error) {
	t.mu.Lock()
	phase := t.active
	if phase != nil && phase.FenceNS == 0 && len(packet) == 100+len("FENCE:"+phase.Mode) {
		phase.FenceNS = time.Since(t.start).Nanoseconds()
	}
	if phase == nil || phase.sent || len(packet) != 356 {
		t.mu.Unlock()
		return false, nil
	}
	phase.sent = true
	phase.original = append([]byte(nil), packet...)
	phase.OriginalSHA256 = packetDigest(packet)
	phase.OriginalBodySHA256 = packetDigest(packet[24:])
	phase.OriginalCipherSHA256 = packetDigest(packet[40:])
	phase.WireLength = len(packet)
	phase.target = target
	modified := append([]byte(nil), packet...)
	mode := phase.Mode
	t.mu.Unlock()
	switch mode {
	case "corrupt":
		modified[len(modified)-1] ^= 1
	case "wrong-hp":
		plain := modified[24:40]
		cipher, err := chacha20.NewUnauthenticatedCipher(t.profile.headerKey, modified[:12])
		if err != nil {
			return true, err
		}
		cipher.XORKeyStream(plain, plain)
		cipher, err = chacha20.NewUnauthenticatedCipher(bytes.Repeat([]byte{4}, 32), modified[:12])
		if err != nil {
			return true, err
		}
		cipher.XORKeyStream(plain, plain)
	case "ambiguous":
		mask := headerMask(t.profile.headerKey, modified)
		for index, header := range []uint32{101, 202, 303} {
			binary.LittleEndian.PutUint32(modified[t.profile.padding[index]:], header^mask)
		}
		t.mu.Lock()
		for _, padding := range t.profile.padding {
			phase.Headers = append(phase.Headers, binary.LittleEndian.Uint32(modified[padding:])^mask)
		}
		t.mu.Unlock()
	}
	t.mu.Lock()
	phase.WireBodySHA256 = packetDigest(modified[24:])
	phase.WireCipherSHA256 = packetDigest(modified[40:])
	t.mu.Unlock()
	destinationSocket := socket
	if mode == "foreign-source" {
		if !target.Addr().IsLoopback() {
			return true, errors.New("negative carrier requires loopback")
		}
		foreign, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if err != nil {
			return true, err
		}
		defer foreign.Close()
		destinationSocket = foreign
		t.mu.Lock()
		phase.DifferentSource = foreign.LocalAddr().String() != socket.LocalAddr().String()
		t.mu.Unlock()
	}
	count := 1
	if mode == "replay" {
		count = 2
	}
	for range count {
		t.mu.Lock()
		phase.WireSHA256 = append(phase.WireSHA256, packetDigest(modified))
		phase.WireNS = append(phase.WireNS, time.Since(t.start).Nanoseconds())
		t.mu.Unlock()
		// The general classifier cannot label deliberately ambiguous or wrong-HP
		// envelopes. Their actual bytes are covered by the dedicated receipt above.
		if mode != "ambiguous" && mode != "wrong-hp" {
			t.bind.observation.observe(modified, false)
		}
		n, err := destinationSocket.WriteToUDPAddrPort(modified, target)
		if err != nil {
			return true, err
		}
		if n != len(modified) {
			return true, io.ErrShortWrite
		}
	}
	return true, nil
}
func (t *transportExercise) restore() error {
	t.bind.mu.Lock()
	socket := t.bind.socket
	t.bind.mu.Unlock()
	if socket == nil {
		return net.ErrClosed
	}
	t.mu.Lock()
	phase := t.active
	packet := append([]byte(nil), phase.original...)
	target := phase.target
	phase.RestoredNS = time.Since(t.start).Nanoseconds()
	t.mu.Unlock()
	t.bind.observation.observe(packet, false)
	n, err := socket.WriteToUDPAddrPort(packet, target)
	if err != nil {
		return err
	}
	if n != len(packet) {
		return io.ErrShortWrite
	}
	return nil
}
func readTransportMarker(socket net.PacketConn, target net.Addr, expected string) error {
	if err := socket.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
		return err
	}
	buffer := make([]byte, 512)
	n, source, err := socket.ReadFrom(buffer)
	if err != nil {
		return err
	}
	if source.String() != target.String() || string(buffer[:n]) != expected {
		return errors.New("transport marker source or payload differs")
	}
	return nil
}
func (t *transportExercise) run(socket net.PacketConn) error {
	if err := socket.SetDeadline(time.Now().Add(10 * time.Second)); err != nil {
		return err
	}
	buffer := make([]byte, 512)
	n, target, err := socket.ReadFrom(buffer)
	if err != nil {
		return err
	}
	if string(buffer[:n]) != "ARM:transport" {
		return errors.New("transport probe arm differs")
	}
	for _, mode := range transportModes {
		phase := &transportPhase{Mode: mode}
		t.mu.Lock()
		t.active = phase
		t.phases = append(t.phases, phase)
		t.mu.Unlock()
		if _, err = socket.WriteTo(negativePayload(mode), target); err != nil {
			return err
		}
		// A small next datagram is queued after the mutated data on this one TUN
		// reader/send path. The authenticated ACK proves that the client passed it.
		if _, err = socket.WriteTo([]byte("FENCE:"+mode), target); err != nil {
			return err
		}
		if err = readTransportMarker(socket, target, "ACK:"+mode); err != nil {
			return err
		}
		t.mu.Lock()
		phase.AckNS = time.Since(t.start).Nanoseconds()
		sent := phase.sent
		t.mu.Unlock()
		if !sent {
			return errors.New("transport mutation was not sent")
		}
		if mode == "corrupt" || mode == "wrong-hp" || mode == "foreign-source" {
			if err = t.restore(); err != nil {
				return err
			}
			if err = readTransportMarker(socket, target, "CONFIRMED:"+mode); err != nil {
				return err
			}
			t.mu.Lock()
			phase.ConfirmedNS = time.Since(t.start).Nanoseconds()
			t.mu.Unlock()
		}
	}
	_, err = socket.WriteTo([]byte("DONE:transport"), target)
	return err
}
func (t *transportExercise) report() []transportPhase {
	t.mu.Lock()
	defer t.mu.Unlock()
	result := make([]transportPhase, len(t.phases))
	for i, phase := range t.phases {
		result[i] = *phase
		result[i].WireSHA256 = append([]string(nil), phase.WireSHA256...)
		result[i].WireNS = append([]int64(nil), phase.WireNS...)
		result[i].Headers = append([]uint32(nil), phase.Headers...)
		result[i].original = nil
	}
	return result
}
