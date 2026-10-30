package main

import (
	"bytes"
	"encoding/binary"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"golang.org/x/crypto/chacha20"
	"net/netip"
	"testing"
)

func TestCookieReportIsAnOwnedSnapshot(t *testing.T) {
	c := &cookieExercise{evidence: cookieEvidence{Phases: []*cookiePhase{{Name: "admission", Sent: []cookieInjection{{SHA256: "before"}}, Responses: []cookieResponse{{Type: 3}}}}}}
	snapshot := c.report()
	c.evidence.Phases[0].Name = "after"
	c.evidence.Phases[0].Sent[0].SHA256 = "after"
	c.evidence.Phases[0].Responses[0].Type = 2
	if snapshot.Phases[0].Name != "admission" || snapshot.Phases[0].Sent[0].SHA256 != "before" || snapshot.Phases[0].Responses[0].Type != 3 {
		t.Fatal("snapshot shares mutable cookie observer state")
	}
}

func TestCookieObserverAuthenticatesReplyBeforeEvidence(t *testing.T) {
	profile, err := selectWireProfile("v3-cpa-trailers")
	if err != nil {
		t.Fatal(err)
	}
	public := bytes.Repeat([]byte{7}, 32)
	c := newCookieExercise(nil, profile, public)
	c.receivers[42] = true
	c.begin("admission")
	message := make([]byte, 148)
	binary.LittleEndian.PutUint32(message, 101)
	binary.LittleEndian.PutUint32(message[4:], 42)
	c.generator.AddMacs(message)
	var checker device.CookieChecker
	checker.Init(c.public)
	reply, err := checker.CreateReply(message, 42, []byte{127, 0, 0, 1, 48, 57}, 303)
	if err != nil {
		t.Fatal(err)
	}
	var body bytes.Buffer
	if err = binary.Write(&body, binary.LittleEndian, reply); err != nil {
		t.Fatal(err)
	}
	packet := append(make([]byte, 16), body.Bytes()...)
	cipher, err := chacha20.NewUnauthenticatedCipher(profile.headerKey, packet[:12])
	if err != nil {
		t.Fatal(err)
	}
	cipher.XORKeyStream(packet[16:], packet[16:])
	remote := netip.MustParseAddrPort("127.0.0.1:12345")
	if !c.intercept(packet, remote) || !c.report().Phases[0].Responses[0].Authenticated {
		t.Fatal("valid cookie was not authenticated")
	}
	packet[len(packet)-1] ^= 1
	if !c.intercept(packet, remote) || c.report().Phases[0].Responses[1].Authenticated {
		t.Fatal("corrupted cookie was accepted")
	}
}
