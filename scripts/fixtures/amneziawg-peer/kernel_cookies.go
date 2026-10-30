package main

import (
	"bytes"
	"crypto/ecdh"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"golang.org/x/crypto/chacha20"
)

type kernelCookieResponse struct {
	cookieResponse
	SHA256 string `json:"sha256"`
}
type kernelCookiePhase struct {
	Name      string                 `json:"name"`
	Sent      []cookieInjection      `json:"sent"`
	Responses []kernelCookieResponse `json:"responses"`
	FenceNS   int64                  `json:"fence_ns"`
	AckNS     int64                  `json:"ack_ns"`
}
type kernelCookieEvidence struct {
	OriginalSHA256 string              `json:"original_sha256"`
	OriginalLength int                 `json:"original_length"`
	Phases         []kernelCookiePhase `json:"phases"`
}

// Replay a captured kernel H2. Public Go APIs verify cookies and compute MAC2;
// they do not create Noise handshakes or transport packets for this exercise.
type kernelCookieExercise struct {
	mu           sync.Mutex
	profile      wireProfile
	start        time.Time
	socket       *net.UDPConn
	remote       netip.AddrPort
	packet, body []byte
	generator    device.CookieGenerator
	evidence     kernelCookieEvidence
	active       int
	failure      error
}

func newKernelCookieExercise(profile wireProfile, socket *net.UDPConn) (*kernelCookieExercise, error) {
	key, err := ecdh.X25519().NewPrivateKey(bytes.Repeat([]byte{7}, 32))
	if err != nil {
		return nil, err
	}
	c := &kernelCookieExercise{profile: profile, socket: socket, start: time.Now(), active: -1}
	var public device.NoisePublicKey
	copy(public[:], key.PublicKey().Bytes())
	c.generator.Init(public)
	return c, nil
}

func (c *kernelCookieExercise) observe(packet []byte, inbound bool, remote netip.AddrPort) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if inbound {
		if !c.remote.IsValid() {
			c.remote = remote
		}
		if remote != c.remote {
			c.failure = errors.New("cookie relay endpoint changed")
			return
		}
	}
	kind, size := 2, 92
	if inbound {
		kind, size = 3, 64
	}
	prefix := c.profile.padding[kind-1]
	if len(packet) < prefix+size {
		return
	}
	body := append([]byte(nil), packet[prefix:prefix+size]...)
	cipher, err := chacha20.NewUnauthenticatedCipher(c.profile.headerKey, packet[:12])
	if err != nil {
		c.failure = err
		return
	}
	cipher.XORKeyStream(body, body)
	if !c.profile.matchesHeader(kind-1, binary.LittleEndian.Uint32(body)) {
		return
	}
	if !inbound {
		if c.packet != nil {
			return
		}
		macs := append([]byte(nil), body...)
		c.generator.AddMacs(macs)
		if !bytes.Equal(macs[60:76], body[60:76]) {
			c.failure = errors.New("captured kernel response MAC1 differs")
			return
		}
		c.packet = append([]byte(nil), packet...)
		c.body = body
		c.evidence.OriginalSHA256 = wireDigest(packet)
		c.evidence.OriginalLength = len(packet)
		return
	}
	if c.body == nil || binary.LittleEndian.Uint32(body[4:8]) != binary.LittleEndian.Uint32(c.body[4:8]) {
		c.failure = errors.New("cookie response receiver differs")
		return
	}
	var reply device.MessageCookieReply
	if err = binary.Read(bytes.NewReader(body), binary.LittleEndian, &reply); err != nil {
		c.failure = err
		return
	}
	authenticated := c.generator.ConsumeReply(&reply)
	if c.active < 0 || len(c.evidence.Phases[c.active].Responses) >= 32 {
		c.failure = errors.New("unexpected cookie response")
		return
	}
	phase := &c.evidence.Phases[c.active]
	phase.Responses = append(phase.Responses, kernelCookieResponse{cookieResponse{Type: 3, Length: len(packet), Authenticated: authenticated, AtNS: time.Since(c.start).Nanoseconds()}, wireDigest(packet)})
}

func (c *kernelCookieExercise) inject(packet []byte) error {
	c.mu.Lock()
	if c.active < 0 || len(c.evidence.Phases[c.active].Sent) >= 32 {
		c.mu.Unlock()
		return errors.New("cookie injection bound")
	}
	phase := &c.evidence.Phases[c.active]
	phase.Sent = append(phase.Sent, cookieInjection{SHA256: wireDigest(packet), AtNS: time.Since(c.start).Nanoseconds()})
	remote := c.remote
	c.mu.Unlock()
	n, err := c.socket.WriteToUDPAddrPort(packet, remote)
	if err == nil && n != len(packet) {
		return errors.New("short cookie injection")
	}
	return err
}

func (c *kernelCookieExercise) run(socket net.PacketConn, disabled bool) error {
	if err := socket.SetReadDeadline(time.Now().Add(10 * time.Second)); err != nil {
		return err
	}
	buffer := make([]byte, 512)
	n, target, err := socket.ReadFrom(buffer)
	if err != nil {
		return err
	}
	if string(buffer[:n]) != "ARM:cookie" {
		return errors.New("cookie response ARM differs")
	}
	time.Sleep(1100 * time.Millisecond)
	names := []string{"invalid-mac1", "admission", "bad-mac2", "valid-mac2", "challenge-budget", "recovery"}
	if disabled {
		names = []string{"invalid-mac1", "admission", "challenge-budget", "recovery"}
	}
	for _, name := range names {
		if name == "challenge-budget" || name == "recovery" {
			time.Sleep(1100 * time.Millisecond)
		}
		c.mu.Lock()
		if c.failure != nil {
			err = c.failure
			c.mu.Unlock()
			return err
		}
		if c.packet == nil {
			c.mu.Unlock()
			return errors.New("no captured kernel H2")
		}
		c.evidence.Phases = append(c.evidence.Phases, kernelCookiePhase{Name: name})
		c.active = len(c.evidence.Phases) - 1
		packet := append([]byte(nil), c.packet...)
		if name == "invalid-mac1" {
			packet[c.profile.padding[1]+60] ^= 1
		}
		if name == "bad-mac2" || name == "valid-mac2" {
			body := append([]byte(nil), c.body...)
			c.generator.AddMacs(body)
			if name == "bad-mac2" {
				body[76] ^= 1
			}
			cipher, _ := chacha20.NewUnauthenticatedCipher(c.profile.headerKey, packet[:12])
			cipher.XORKeyStream(packet[c.profile.padding[1]:c.profile.padding[1]+92], body)
		}
		c.mu.Unlock()
		count := map[string]int{"invalid-mac1": 4, "admission": 11, "bad-mac2": 1, "valid-mac2": 1, "challenge-budget": 21}[name]
		for range count {
			if err = c.inject(packet); err != nil {
				return err
			}
		}
		c.mu.Lock()
		c.evidence.Phases[c.active].FenceNS = time.Since(c.start).Nanoseconds()
		c.mu.Unlock()
		if _, err = socket.WriteTo([]byte("FENCE:"+name), target); err != nil {
			return err
		}
		if err = readTransportMarker(socket, target, "ACK:"+name); err != nil {
			return err
		}
		c.mu.Lock()
		c.evidence.Phases[c.active].AckNS = time.Since(c.start).Nanoseconds()
		c.mu.Unlock()
	}
	_, err = socket.WriteTo([]byte("DONE:cookie"), target)
	return err
}

func (c *kernelCookieExercise) report() kernelCookieEvidence {
	c.mu.Lock()
	defer c.mu.Unlock()
	result := c.evidence
	result.Phases = append([]kernelCookiePhase(nil), result.Phases...)
	for i := range result.Phases {
		result.Phases[i].Sent = append([]cookieInjection(nil), result.Phases[i].Sent...)
		result.Phases[i].Responses = append([]kernelCookieResponse(nil), result.Phases[i].Responses...)
	}
	return result
}
