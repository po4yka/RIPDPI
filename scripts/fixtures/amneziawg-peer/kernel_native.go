package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"os"
	"sync"
	"time"
)

type nativeAction struct {
	Origin  string `json:"origin"`
	SHA256  string `json:"sha256"`
	Forward bool   `json:"forward"`
	AtNS    int64  `json:"at_ns"`
}
type nativeEvidence struct {
	StartNS int64          `json:"start_unix_ns"`
	Actions []nativeAction `json:"actions"`
	A       string         `json:"a"`
	B       string         `json:"b"`
	C       string         `json:"c"`
	Cookie  string         `json:"cookie"`
	MAC2    bool           `json:"mac2"`
}
type nativeKernelExercise struct {
	mu               sync.Mutex
	profile          wireProfile
	socket           *net.UDPConn
	remote           netip.AddrPort
	start            time.Time
	evidence         nativeEvidence
	verifier         *kernelCookieExercise
	packets          [][]byte
	b, c, confirmed  chan struct{}
	current          uint32
	recoveryReceiver uint32
	failure          error
	disabled         bool
	bound            bool
}

func newNativeKernel(profile wireProfile, socket *net.UDPConn, disabled bool) (*nativeKernelExercise, error) {
	verifier, err := newKernelCookieExercise(profile, nil)
	if err != nil {
		return nil, err
	}
	n := &nativeKernelExercise{profile: profile, socket: socket, start: time.Now(), verifier: verifier, disabled: disabled, b: make(chan struct{}), c: make(chan struct{}), confirmed: make(chan struct{})}
	n.evidence.StartNS = n.start.UnixNano()
	return n, nil
}
func nativeType(profile wireProfile, packet []byte) (int, []byte) {
	for i, size := range []int{148, 92, 64, 32} {
		if len(packet) < profile.padding[i]+size {
			continue
		}
		body, err := cookieWireBody(profile, i+1, size, packet)
		if err == nil {
			return i + 1, body
		}
	}
	return 0, nil
}
func (n *nativeKernelExercise) record(origin string, packet []byte, forward bool) error {
	if len(n.evidence.Actions) >= 1024 {
		return errors.New("native journal bound")
	}
	n.evidence.Actions = append(n.evidence.Actions, nativeAction{origin, wireDigest(packet), forward, time.Since(n.start).Nanoseconds()})
	return nil
}

// The relay loop is the sole receiver. Hold its mutex through each send so the
// action order and injection order agree with actual socket writes.
func (n *nativeKernelExercise) handle(packet []byte, inbound bool, remote netip.AddrPort) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	kind, body := nativeType(n.profile, packet)
	target := n.remote
	forward := true
	origin := "kernel"
	if inbound {
		origin = "client"
		if !n.remote.IsValid() {
			n.remote = remote
		}
		if remote != n.remote {
			return errors.New("native client source changed")
		}
		target = netip.MustParseAddrPort("127.0.0.1:51820")
		if len(n.packets) == 0 {
			forward = false
		}
		if kind == 1 {
			forward = false
			if !n.bound {
				n.bound = true
				if err := json.NewEncoder(os.Stdout).Encode(map[string]bool{"client_bound": true}); err != nil {
					return err
				}
			}
		}
		if kind == 2 && len(n.packets) == 2 {
			forward = false
		}
		if kind == 2 && len(n.packets) == 3 {
			if binary.LittleEndian.Uint32(body[8:12]) != n.current {
				return errors.New("native recovery H2 receiver differs")
			}
			n.recoveryReceiver = binary.LittleEndian.Uint32(body[4:8])
		}
		if kind == 3 {
			if len(n.packets) != 2 || binary.LittleEndian.Uint32(body[4:8]) != n.current {
				return errors.New("native cookie receiver differs")
			}
			// The public verifier authenticates B's cookie. The original datagram goes
			// to the kernel; no Go-generated handshake or MAC2 is sent.
			n.verifier.active = 0
			n.verifier.evidence.Phases = []kernelCookiePhase{{Name: "native"}}
			n.verifier.observe(packet, true, remote)
			if n.verifier.failure != nil {
				return n.verifier.failure
			}
			if len(n.verifier.evidence.Phases[0].Responses) != 1 || !n.verifier.evidence.Phases[0].Responses[0].Authenticated {
				return errors.New("native cookie authentication failed")
			}
			n.evidence.Cookie = wireDigest(packet)
		}
	} else {
		if !target.IsValid() {
			return errors.New("native kernel packet before client bind")
		}
		if kind == 1 {
			n.packets = append(n.packets, append([]byte(nil), packet...))
			n.current = binary.LittleEndian.Uint32(body[4:8])
			switch len(n.packets) {
			case 1:
				n.evidence.A = wireDigest(packet)
			case 2:
				n.evidence.B = wireDigest(packet)
				forward = false
				seed := append([]byte(nil), body...)
				n.verifier.generator.AddMacs(seed)
				if !bytes.Equal(seed[116:132], body[116:132]) {
					return errors.New("native B MAC1 invalid")
				}
				// CookieGenerator verification uses the initiation MAC1, not H2 offsets.
				n.verifier.body = body
				n.verifier.remote = n.remote
				close(n.b)
			case 3:
				n.evidence.C = wireDigest(packet)
				if !n.disabled {
					if n.evidence.Cookie == "" {
						return errors.New("native retry preceded cookie")
					}
					expected := append([]byte(nil), body...)
					n.verifier.generator.AddMacs(expected)
					if bytes.Equal(body[132:148], make([]byte, 16)) || !bytes.Equal(body[132:148], expected[132:148]) {
						return errors.New("native retry MAC2 invalid")
					}
					n.evidence.MAC2 = true
				}
				close(n.c)
			default:
				return errors.New("unexpected extra native initiation")
			}
		}
		if kind == 4 && n.recoveryReceiver != 0 && binary.LittleEndian.Uint32(body[4:8]) == n.recoveryReceiver {
			select {
			case <-n.confirmed:
			default:
				close(n.confirmed)
			}
		}
	}
	if err := n.record(origin, packet, forward); err != nil {
		return err
	}
	if forward {
		written, err := n.socket.WriteToUDPAddrPort(packet, target)
		if err != nil {
			return err
		}
		if written != len(packet) {
			return errors.New("short native relay send")
		}
	}
	return nil
}
func (n *nativeKernelExercise) inject(packet []byte) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	if err := n.record("inject", packet, true); err != nil {
		return err
	}
	written, err := n.socket.WriteToUDPAddrPort(packet, n.remote)
	if err == nil && written != len(packet) {
		return errors.New("short native injection")
	}
	return err
}
func nativeWait(signal <-chan struct{}) error {
	select {
	case <-signal:
		return nil
	case <-time.After(6 * time.Second):
		return errors.New("native transition deadline")
	}
}
func (n *nativeKernelExercise) run(socket net.PacketConn) error {
	socket.SetReadDeadline(time.Now().Add(15 * time.Second))
	buffer := make([]byte, 512)
	size, target, err := socket.ReadFrom(buffer)
	if err != nil {
		return err
	}
	if string(buffer[:size]) != "ARM:cookie" {
		return errors.New("native ARM differs")
	}
	fence := func(name string) error {
		if err := socket.SetWriteDeadline(time.Now().Add(2 * time.Second)); err != nil {
			return err
		}
		if _, err := socket.WriteTo([]byte("FENCE:"+name), target); err != nil {
			return err
		}
		return readTransportMarker(socket, target, "ACK:"+name)
	}
	if err = fence("baseline"); err != nil {
		return err
	}
	time.Sleep(2300 * time.Millisecond)
	if err = socket.SetWriteDeadline(time.Now().Add(2 * time.Second)); err != nil {
		return err
	}
	if _, err = socket.WriteTo([]byte("TRIGGER:native"), target); err != nil {
		return err
	}
	if err = readTransportMarker(socket, target, "ACK:trigger"); err != nil {
		return err
	}
	if err = nativeWait(n.b); err != nil {
		return err
	}
	n.mu.Lock()
	a, b := append([]byte(nil), n.packets[0]...), append([]byte(nil), n.packets[1]...)
	n.mu.Unlock()
	for range 10 {
		if err = n.inject(a); err != nil {
			return err
		}
	}
	if err = n.inject(b); err != nil {
		return err
	}
	if err = fence("load"); err != nil {
		return err
	}
	if err = nativeWait(n.c); err != nil {
		return err
	}
	if err = nativeWait(n.confirmed); err != nil {
		return err
	}
	if err = fence("recovery"); err != nil {
		return err
	}
	_, err = socket.WriteTo([]byte("DONE:cookie"), target)
	return err
}
func (n *nativeKernelExercise) report() nativeEvidence {
	n.mu.Lock()
	defer n.mu.Unlock()
	result := n.evidence
	result.Actions = append([]nativeAction(nil), result.Actions...)
	return result
}
