package main

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"net"
	"net/netip"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tai64n"
	"golang.org/x/crypto/chacha20"
)

type cookieInjection struct {
	SHA256 string `json:"sha256"`
	AtNS   int64  `json:"at_ns"`
}
type cookieResponse struct {
	Type          int   `json:"type"`
	Length        int   `json:"length"`
	Authenticated bool  `json:"authenticated"`
	AtNS          int64 `json:"at_ns"`
}
type cookiePhase struct {
	Name      string            `json:"name"`
	Sent      []cookieInjection `json:"sent"`
	Responses []cookieResponse  `json:"responses"`
	FenceNS   int64             `json:"fence_ns"`
	AckNS     int64             `json:"ack_ns"`
}
type cookieEvidence struct {
	Phases            []*cookiePhase `json:"phases"`
	AdmissionWindowNS int64          `json:"admission_window_ns"`
	RecoveryConfirmed bool           `json:"recovery_confirmed"`
}

// The cookie probe uses only public upstream Noise/cookie APIs and the same
// owned loopback socket as normal transport. No raw keys or cookie bytes leave it.
type cookieExercise struct {
	mu          sync.Mutex
	bind        *loopbackBind
	profile     wireProfile
	device      *device.Device
	public      device.NoisePublicKey
	generator   device.CookieGenerator
	lastCreated tai64n.Timestamp
	remote      netip.AddrPort
	receivers   map[uint32]bool
	release     uint32
	started     time.Time
	active      *cookiePhase
	evidence    cookieEvidence
	failure     string
}

func newCookieExercise(bind *loopbackBind, profile wireProfile, public []byte) *cookieExercise {
	c := &cookieExercise{bind: bind, profile: profile, receivers: make(map[uint32]bool), started: time.Now()}
	copy(c.public[:], public)
	c.generator.Init(c.public)
	return c
}

func (c *cookieExercise) intercept(packet []byte, remote netip.AddrPort) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.remote.IsValid() {
		c.remote = remote
	}
	if remote != c.remote {
		c.failure = "cookie carrier source changed"
		return true
	}
	for _, kind := range []int{2, 3} {
		prefix, size := c.profile.padding[kind-1], 92
		if kind == 3 {
			size = 64
		}
		if len(packet) < prefix+size {
			continue
		}
		body := append([]byte(nil), packet[prefix:prefix+size]...)
		cipher, err := chacha20.NewUnauthenticatedCipher(c.profile.headerKey, packet[:12])
		if err != nil {
			return false
		}
		cipher.XORKeyStream(body, body)
		if !c.profile.matchesHeader(kind-1, binary.LittleEndian.Uint32(body)) {
			continue
		}
		offset := 8
		if kind == 3 {
			offset = 4
		}
		receiver := binary.LittleEndian.Uint32(body[offset:])
		if !c.receivers[receiver] {
			continue
		}
		authenticated := false
		if kind == 3 {
			var reply device.MessageCookieReply
			if binary.Read(bytes.NewReader(body), binary.LittleEndian, &reply) == nil {
				authenticated = c.generator.ConsumeReply(&reply)
			}
		}
		if c.active != nil {
			if len(c.active.Responses) >= 64 {
				c.failure = "cookie response bound exceeded"
				return true
			}
			c.active.Responses = append(c.active.Responses, cookieResponse{Type: kind, Length: len(packet), Authenticated: authenticated, AtNS: time.Since(c.started).Nanoseconds()})
		}
		return kind != 2 || receiver != c.release
	}
	return false
}

func (c *cookieExercise) initiation(clearMAC2 bool) ([]byte, uint32, error) {
	peer := c.device.LookupPeer(c.public)
	if peer == nil {
		return nil, 0, errors.New("cookie probe peer missing")
	}
	// Upstream quantizes TAI64N to ~16.8 ms. A new ephemeral key in the same
	// timestamp bucket is still a Noise replay, so wait for an actual new stamp.
	deadline := time.Now().Add(250 * time.Millisecond)
	for !tai64n.Now().After(c.lastCreated) {
		if time.Now().After(deadline) {
			return nil, 0, errors.New("fresh Noise timestamp deadline")
		}
		time.Sleep(time.Millisecond)
	}
	message, err := c.device.CreateMessageInitiation(peer)
	if err != nil {
		return nil, 0, err
	}
	c.lastCreated = tai64n.Now()
	var buffer bytes.Buffer
	if err = binary.Write(&buffer, binary.LittleEndian, message); err != nil {
		return nil, 0, err
	}
	body := buffer.Bytes()
	c.generator.AddMacs(body)
	if clearMAC2 {
		clear(body[132:148])
	}
	c.mu.Lock()
	c.receivers[message.Sender] = true
	c.mu.Unlock()
	return body, message.Sender, nil
}
func (c *cookieExercise) envelope(body []byte) ([]byte, error) {
	prefix := make([]byte, c.profile.padding[0])
	if _, err := rand.Read(prefix); err != nil {
		return nil, err
	}
	cipher, err := chacha20.NewUnauthenticatedCipher(c.profile.headerKey, prefix[:12])
	if err != nil {
		return nil, err
	}
	packet := append(prefix, body...)
	cipher.XORKeyStream(packet[len(prefix):], packet[len(prefix):])
	return packet, nil
}
func (c *cookieExercise) send(packet []byte) error {
	c.bind.mu.Lock()
	socket := c.bind.socket
	c.bind.mu.Unlock()
	if socket == nil {
		return net.ErrClosed
	}
	c.mu.Lock()
	remote := c.remote
	c.mu.Unlock()
	if !remote.IsValid() {
		return errors.New("cookie probe has no source-bound endpoint")
	}
	digest := sha256.Sum256(packet)
	c.mu.Lock()
	c.active.Sent = append(c.active.Sent, cookieInjection{SHA256: hex.EncodeToString(digest[:]), AtNS: time.Since(c.started).Nanoseconds()})
	c.mu.Unlock()
	n, err := socket.WriteToUDPAddrPort(packet, remote)
	if err != nil {
		return err
	}
	if n != len(packet) {
		return errors.New("short cookie probe send")
	}
	return nil
}

func (c *cookieExercise) begin(name string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.active = &cookiePhase{Name: name}
	c.evidence.Phases = append(c.evidence.Phases, c.active)
}
func (c *cookieExercise) fence(socket net.PacketConn, target net.Addr) error {
	c.mu.Lock()
	name := c.active.Name
	c.mu.Unlock()
	if err := socket.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
		return err
	}
	// Record the request before enqueueing it. The authenticated ACK is the
	// receive-queue barrier, not WriteTo itself or a fixed silence delay.
	c.mu.Lock()
	c.active.FenceNS = time.Since(c.started).Nanoseconds()
	c.mu.Unlock()
	if _, err := socket.WriteTo([]byte("FENCE:"+name), target); err != nil {
		return err
	}
	buffer := make([]byte, 256)
	n, source, err := socket.ReadFrom(buffer)
	if err != nil {
		return err
	}
	if source.String() != target.String() || string(buffer[:n]) != "ACK:"+name {
		return errors.New("cookie processing fence differs")
	}
	c.mu.Lock()
	c.active.AckNS = time.Since(c.started).Nanoseconds()
	failure := c.failure
	c.mu.Unlock()
	if failure != "" {
		return errors.New(failure)
	}
	return nil
}
func (c *cookieExercise) phase(name string, packet []byte, count int, socket net.PacketConn, target net.Addr) error {
	c.begin(name)
	for i := 0; i < count; i++ {
		if err := c.send(packet); err != nil {
			return err
		}
	}
	return c.fence(socket, target)
}

func (c *cookieExercise) run(socket net.PacketConn, disabled bool) error {
	if err := socket.SetDeadline(time.Now().Add(15 * time.Second)); err != nil {
		return err
	}
	buffer := make([]byte, 256)
	n, target, err := socket.ReadFrom(buffer)
	if err != nil {
		return err
	}
	if string(buffer[:n]) != "ARM:cookie" {
		return errors.New("cookie probe arm differs")
	}
	// Wait for the initial handshake's one-second admission window to reset.
	time.Sleep(1100 * time.Millisecond)
	window := time.Now()
	a, _, err := c.initiation(true)
	if err != nil {
		return err
	}
	bad := append([]byte(nil), a...)
	bad[116] ^= 1
	badWire, err := c.envelope(bad)
	if err != nil {
		return err
	}
	if err = c.phase("invalid-mac1", badWire, 4, socket, target); err != nil {
		return err
	}
	wire, err := c.envelope(a)
	if err != nil {
		return err
	}
	c.begin("admission")
	for range 9 {
		if err = c.send(wire); err != nil {
			return err
		}
	}
	edge, _, err := c.initiation(true)
	if err != nil {
		return err
	}
	edgeWire, err := c.envelope(edge)
	if err != nil {
		return err
	}
	if err = c.send(edgeWire); err != nil {
		return err
	}
	// Restore A's MAC1 association before the eleventh packet's challenge.
	c.generator.AddMacs(a)
	if err = c.send(wire); err != nil {
		return err
	}
	if err = c.fence(socket, target); err != nil {
		return err
	}
	if !disabled {
		b, _, err := c.initiation(false)
		if err != nil {
			return err
		}
		bad = append([]byte(nil), b...)
		bad[132] ^= 1
		badWire, err = c.envelope(bad)
		if err != nil {
			return err
		}
		if err = c.phase("bad-mac2", badWire, 1, socket, target); err != nil {
			return err
		}
		wire, err = c.envelope(b)
		if err != nil {
			return err
		}
		if err = c.phase("valid-mac2", wire, 1, socket, target); err != nil {
			return err
		}
	}
	if disabled {
		fresh, _, err := c.initiation(true)
		if err != nil {
			return err
		}
		wire, err = c.envelope(fresh)
		if err != nil {
			return err
		}
		if err = c.phase("exhausted-noise", wire, 1, socket, target); err != nil {
			return err
		}
	}
	c.mu.Lock()
	c.evidence.AdmissionWindowNS = time.Since(window).Nanoseconds()
	c.mu.Unlock()
	if time.Since(window) >= 750*time.Millisecond {
		return errors.New("cookie admission crossed its bounded window")
	}
	time.Sleep(1100 * time.Millisecond)
	a, _, err = c.initiation(true)
	if err != nil {
		return err
	}
	wire, err = c.envelope(a)
	if err != nil {
		return err
	}
	window = time.Now()
	if err = c.phase("challenge-budget", wire, 21, socket, target); err != nil {
		return err
	}
	if time.Since(window) >= 750*time.Millisecond {
		return errors.New("cookie challenge crossed its bounded window")
	}
	time.Sleep(1100 * time.Millisecond)
	before, err := c.device.IpcGet()
	if err != nil {
		return err
	}
	counters, err := parsePeerCounters(before)
	if err != nil {
		return err
	}
	a, receiver, err := c.initiation(false)
	if err != nil {
		return err
	}
	c.begin("recovery")
	c.mu.Lock()
	c.release = receiver
	c.mu.Unlock()
	wire, err = c.envelope(a)
	if err != nil {
		return err
	}
	if err = c.send(wire); err != nil {
		return err
	}
	deadline := time.Now().Add(2 * time.Second)
	confirmed := false
	for time.Now().Before(deadline) {
		raw, err := c.device.IpcGet()
		if err != nil {
			return err
		}
		current, err := parsePeerCounters(raw)
		if err != nil {
			return err
		}
		if current["last_handshake_time_sec"] != counters["last_handshake_time_sec"] || current["last_handshake_time_nsec"] != counters["last_handshake_time_nsec"] {
			confirmed = true
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if !confirmed {
		return errors.New("cookie recovery handshake not confirmed")
	}
	if err = c.fence(socket, target); err != nil {
		return err
	}
	c.mu.Lock()
	c.evidence.RecoveryConfirmed = true
	c.mu.Unlock()
	_, err = socket.WriteTo([]byte("DONE:cookie"), target)
	return err
}
func (c *cookieExercise) report() cookieEvidence {
	c.mu.Lock()
	defer c.mu.Unlock()
	snapshot := c.evidence
	snapshot.Phases = make([]*cookiePhase, len(c.evidence.Phases))
	for i, phase := range c.evidence.Phases {
		copied := *phase
		copied.Sent = append([]cookieInjection(nil), phase.Sent...)
		copied.Responses = append([]cookieResponse(nil), phase.Responses...)
		snapshot.Phases[i] = &copied
	}
	return snapshot
}
