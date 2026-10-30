package main

import (
	"bytes"
	"crypto/ecdh"
	"encoding/binary"
	"strings"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"golang.org/x/crypto/chacha20"
)

func TestKernelCookieCaptureChecksMutationInsteadOfDifferentHash(t *testing.T) {
	profile, _ := selectWireProfile("v3-cpa-trailers")
	c, _ := newKernelCookieExercise(profile, nil)
	body := make([]byte, 92)
	binary.LittleEndian.PutUint32(body, 202)
	binary.LittleEndian.PutUint32(body[4:], 123)
	c.generator.AddMacs(body)
	envelope := func(value []byte) []byte {
		packet := append(bytes.Repeat([]byte{6}, 20), make([]byte, 92)...)
		cipher, _ := chacha20.NewUnauthenticatedCipher(profile.headerKey, packet[:12])
		cipher.XORKeyStream(packet[20:], value)
		return packet
	}
	original := envelope(body)
	changed := append([]byte(nil), body...)
	changed[60] ^= 1
	good := envelope(changed)
	report := kernelRelayReport{Port: 50000, StartNS: time.Second.Nanoseconds(), EndNS: 2 * time.Second.Nanoseconds(), Cookies: &kernelCookieEvidence{OriginalSHA256: wireDigest(original), Phases: []kernelCookiePhase{{Name: "invalid-mac1", Sent: []cookieInjection{{SHA256: wireDigest(good), AtNS: 100}}, FenceNS: 500}}}}
	packets := []capturedUDP{{source: 51820, destination: 50000, payload: original}, {source: 50000, destination: 40000, payload: good, at: time.Unix(1, 200)}}
	if err := checkKernelCookieMutations(profile, report, packets); err != nil {
		t.Fatal(err)
	}
	// Preserve total capture count while moving one repeated injection outside
	// its phase. A search which reuses one record would accept this evidence.
	report.Cookies.Phases[0].Sent = append(report.Cookies.Phases[0].Sent, cookieInjection{SHA256: wireDigest(good), AtNS: 110})
	outside := packets[1]
	outside.at = time.Unix(1, 900)
	if err := checkKernelCookieMutations(profile, report, append(packets, outside)); err == nil {
		t.Fatal("duplicate moved outside phase accepted")
	}
	inside := packets[1]
	inside.at = time.Unix(1, 210)
	if err := checkKernelCookieMutations(profile, report, append(packets, inside)); err != nil {
		t.Fatal(err)
	}
	report.Cookies.Phases[0].Sent = report.Cookies.Phases[0].Sent[:1]
	changed = append([]byte(nil), body...)
	changed[20] ^= 1
	packets[1].payload = envelope(changed)
	report.Cookies.Phases[0].Sent[0].SHA256 = wireDigest(packets[1].payload)
	if err := checkKernelCookieMutations(profile, report, packets); err == nil {
		t.Fatal("different hash hid a Noise mutation")
	}
	report.Cookies.Phases[0].Name = "valid-mac2"
	if err := checkKernelCookieMutations(profile, report, packets); err == nil {
		t.Fatal("valid MAC2 claimed without authenticated cookie")
	}
}

func TestKernelCookieReportOwnsItsSlices(t *testing.T) {
	c := &kernelCookieExercise{evidence: kernelCookieEvidence{Phases: []kernelCookiePhase{{Sent: []cookieInjection{{SHA256: "original"}}, Responses: []kernelCookieResponse{{SHA256: "reply"}}}}}}
	copy := c.report()
	copy.Phases[0].Sent[0].SHA256 = "changed"
	copy.Phases[0].Responses[0].SHA256 = "changed"
	if got := c.report(); got.Phases[0].Sent[0].SHA256 != "original" || got.Phases[0].Responses[0].SHA256 != "reply" {
		t.Fatal("report shared mutable slices")
	}
}

func TestKernelCookieResponseMustBeCapturedInsideItsPhase(t *testing.T) {
	profile, _ := selectWireProfile("v3-cpa-trailers")
	c, _ := newKernelCookieExercise(profile, nil)
	body := make([]byte, 92)
	binary.LittleEndian.PutUint32(body, 202)
	binary.LittleEndian.PutUint32(body[4:], 123)
	c.generator.AddMacs(body)
	key, _ := ecdh.X25519().NewPrivateKey(bytes.Repeat([]byte{7}, 32))
	var public device.NoisePublicKey
	copy(public[:], key.PublicKey().Bytes())
	var checker device.CookieChecker
	checker.Init(public)
	reply, err := checker.CreateReply(body, 123, []byte("owned-source"), 303)
	if err != nil {
		t.Fatal(err)
	}
	var encoded bytes.Buffer
	if err = binary.Write(&encoded, binary.LittleEndian, reply); err != nil {
		t.Fatal(err)
	}
	envelope := func(prefix int, value []byte) []byte {
		packet := append(bytes.Repeat([]byte{6}, prefix), make([]byte, len(value))...)
		cipher, _ := chacha20.NewUnauthenticatedCipher(profile.headerKey, packet[:12])
		cipher.XORKeyStream(packet[prefix:], value)
		return packet
	}
	h2, h3 := envelope(20, body), envelope(16, encoded.Bytes())
	report := kernelRelayReport{Port: 50000, StartNS: time.Second.Nanoseconds(), EndNS: 2 * time.Second.Nanoseconds(), Cookies: &kernelCookieEvidence{OriginalSHA256: wireDigest(h2), Phases: []kernelCookiePhase{{Name: "admission", Sent: []cookieInjection{{SHA256: wireDigest(h2), AtNS: 100}}, FenceNS: 500, AckNS: 800, Responses: []kernelCookieResponse{{SHA256: wireDigest(h3)}}}}}}
	packets := []capturedUDP{{source: 51820, destination: 50000, payload: h2}, {source: 50000, destination: 40000, payload: h2, at: time.Unix(1, 200)}, {source: 50000, destination: 51820, payload: h3, at: time.Unix(1, 300)}}
	if err = checkKernelCookieMutations(profile, report, packets); err != nil {
		t.Fatal(err)
	}
	packets[2].at = time.Unix(1, 900)
	if err = checkKernelCookieMutations(profile, report, packets); err == nil || !strings.Contains(err.Error(), "outside its phase") {
		t.Fatal("valid cookie moved outside phase accepted", err)
	}
}
