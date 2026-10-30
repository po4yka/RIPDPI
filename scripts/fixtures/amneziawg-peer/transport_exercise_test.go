package main

import (
	"bytes"
	"encoding/binary"
	"golang.org/x/crypto/chacha20"
	"net"
	"testing"
	"time"
)

func TestMalformedIPChangesOnlyOneDeclaredLength(t *testing.T) {
	exercise := &transportExercise{active: &transportPhase{Mode: "malformed-ip"}}
	packet := bytes.Repeat([]byte{0x55}, 300)
	packet[16] = 0x45
	binary.BigEndian.PutUint16(packet[18:20], 284)
	expected := append([]byte(nil), packet...)
	expected[18], expected[19] = 0, 0
	exercise.mutateIP([][]byte{packet}, []int{284}, 16, 1)
	if !exercise.active.MalformedIP || !bytes.Equal(packet, expected) {
		t.Fatal("malformed injection changed routing/buffer bytes")
	}
	next := bytes.Repeat([]byte{0x55}, 300)
	next[16] = 0x45
	expected = append([]byte(nil), next...)
	exercise.mutateIP([][]byte{next}, []int{284}, 16, 1)
	if !bytes.Equal(next, expected) {
		t.Fatal("a second packet was also changed")
	}
}

func TestAmbiguousWireKeepsAuthenticatedBody(t *testing.T) {
	source, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer source.Close()
	sink, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer sink.Close()
	if err = sink.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	profile, err := selectWireProfile("v3-cpa-trailers")
	if err != nil {
		t.Fatal(err)
	}
	packet := bytes.Repeat([]byte{0x55}, 356)
	binary.LittleEndian.PutUint32(packet[24:28], 425)
	cipher, err := chacha20.NewUnauthenticatedCipher(profile.headerKey, packet[:12])
	if err != nil {
		t.Fatal(err)
	}
	cipher.XORKeyStream(packet[24:], packet[24:])
	exercise := newTransportExercise(&loopbackBind{}, profile)
	exercise.active = &transportPhase{Mode: "ambiguous"}
	handled, err := exercise.send(packet, source, sink.LocalAddr().(*net.UDPAddr).AddrPort())
	if err != nil || !handled {
		t.Fatalf("send: %v %v", handled, err)
	}
	actual := make([]byte, 512)
	n, _, err := sink.ReadFromUDP(actual)
	if err != nil {
		t.Fatal(err)
	}
	actual = actual[:n]
	if !bytes.Equal(packet[:12], actual[:12]) || !bytes.Equal(packet[24:], actual[24:]) {
		t.Fatal("ambiguity changed the nonce or authenticated body")
	}
	mask := headerMask(profile.headerKey, actual)
	for index, want := range []uint32{101, 202, 303, 425} {
		if got := binary.LittleEndian.Uint32(actual[profile.padding[index]:]) ^ mask; got != want {
			t.Fatalf("candidate %d: got %d want %d", index, got, want)
		}
	}
}

func TestTransportReportIsAnOwnedSnapshot(t *testing.T) {
	exercise := &transportExercise{phases: []*transportPhase{{WireSHA256: []string{"before"}, WireNS: []int64{1}, Headers: []uint32{101}, original: []byte{1}}}}
	snapshot := exercise.report()
	exercise.phases[0].WireSHA256[0] = "after"
	exercise.phases[0].WireNS[0] = 2
	exercise.phases[0].Headers[0] = 202
	if snapshot[0].WireSHA256[0] != "before" || snapshot[0].WireNS[0] != 1 || snapshot[0].Headers[0] != 101 || snapshot[0].original != nil {
		t.Fatal("report shares mutable/raw packet state")
	}
}

func TestWrongHPChangesOnlyProtectedHeader(t *testing.T) {
	source, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer source.Close()
	sink, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer sink.Close()
	if err = sink.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	profile, err := selectWireProfile("v3-cpa-trailers")
	if err != nil {
		t.Fatal(err)
	}
	packet := bytes.Repeat([]byte{0x55}, 356)
	binary.LittleEndian.PutUint32(packet[24:28], 425)
	cipher, err := chacha20.NewUnauthenticatedCipher(profile.headerKey, packet[:12])
	if err != nil {
		t.Fatal(err)
	}
	cipher.XORKeyStream(packet[24:40], packet[24:40])
	exercise := newTransportExercise(&loopbackBind{}, profile)
	exercise.active = &transportPhase{Mode: "wrong-hp"}
	handled, err := exercise.send(packet, source, sink.LocalAddr().(*net.UDPAddr).AddrPort())
	if err != nil || !handled {
		t.Fatalf("send: %v %v", handled, err)
	}
	actual := make([]byte, 512)
	n, _, err := sink.ReadFromUDP(actual)
	if err != nil {
		t.Fatal(err)
	}
	actual = actual[:n]
	if !bytes.Equal(packet[:24], actual[:24]) || !bytes.Equal(packet[40:], actual[40:]) || bytes.Equal(packet[24:40], actual[24:40]) {
		t.Fatal("wrong-HP injection did not isolate the protected 16-byte header")
	}
	cipher, err = chacha20.NewUnauthenticatedCipher(bytes.Repeat([]byte{4}, 32), actual[:12])
	if err != nil {
		t.Fatal(err)
	}
	cipher.XORKeyStream(actual[24:40], actual[24:40])
	if binary.LittleEndian.Uint32(actual[24:28]) != 425 {
		t.Fatal("wrong key did not recover the original header")
	}
}
