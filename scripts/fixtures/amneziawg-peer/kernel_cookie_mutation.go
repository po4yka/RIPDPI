package main

import (
	"bytes"
	"encoding/binary"
	"errors"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"golang.org/x/crypto/chacha20"
)

func cookieWireBody(profile wireProfile, kind, size int, packet []byte) ([]byte, error) {
	prefix := profile.padding[kind-1]
	if len(packet) < prefix+size {
		return nil, errors.New("short captured cookie envelope")
	}
	body := append([]byte(nil), packet[prefix:prefix+size]...)
	cipher, err := chacha20.NewUnauthenticatedCipher(profile.headerKey, packet[:12])
	if err != nil {
		return nil, err
	}
	cipher.XORKeyStream(body, body)
	if !profile.matchesHeader(kind-1, binary.LittleEndian.Uint32(body)) {
		return nil, errors.New("cookie envelope header differs")
	}
	return body, nil
}

// Rebuild MAC2 from authenticated captured H3, then compare the complete H2
// envelope, including Noise, padding and trailers, to every injected packet.
func checkKernelCookieMutations(profile wireProfile, report kernelRelayReport, packets []capturedUDP) error {
	c, err := newKernelCookieExercise(profile, nil)
	if err != nil {
		return err
	}
	byDigest := make(map[string][]capturedUDP)
	consumed := make(map[string]map[int]bool)
	var original []byte
	for _, packet := range packets {
		digest := wireDigest(packet.payload)
		if packet.source == 51820 && digest == report.Cookies.OriginalSHA256 {
			original = packet.payload
		}
		if packet.source == report.Port && packet.destination != 51820 {
			byDigest[digest] = append(byDigest[digest], packet)
		}
		if packet.destination == 51820 {
			byDigest[digest] = append(byDigest[digest], packet)
		}
	}
	if original == nil {
		return errors.New("missing original kernel cookie response")
	}
	body, err := cookieWireBody(profile, 2, 92, original)
	if err != nil {
		return err
	}
	macs := append([]byte(nil), body...)
	c.generator.AddMacs(macs)
	if !bytes.Equal(macs[60:76], body[60:76]) {
		return errors.New("original kernel MAC1 invalid")
	}
	for _, phase := range report.Cookies.Phases {
		wanted := append([]byte(nil), original...)
		plain := append([]byte(nil), body...)
		switch phase.Name {
		case "invalid-mac1":
			plain[60] ^= 1
		case "bad-mac2", "valid-mac2":
			c.generator.AddMacs(plain)
			if bytes.Equal(plain[76:92], make([]byte, 16)) {
				return errors.New("captured challenge did not yield MAC2")
			}
			if phase.Name == "bad-mac2" {
				plain[76] ^= 1
			}
		}
		cipher, _ := chacha20.NewUnauthenticatedCipher(profile.headerKey, original[:12])
		cipher.XORKeyStream(wanted[profile.padding[1]:profile.padding[1]+92], plain)
		for _, sent := range phase.Sent {
			if wireDigest(wanted) != sent.SHA256 {
				return errors.New("cookie phase MAC1/MAC2 mutation differs")
			}
			found := false
			for index, packet := range byDigest[sent.SHA256] {
				at := packet.at.Sub(time.Unix(0, report.StartNS)).Nanoseconds()
				if !consumed[sent.SHA256][index] && packet.source == report.Port && at >= sent.AtNS && at <= phase.FenceNS && bytes.Equal(packet.payload, wanted) {
					if consumed[sent.SHA256] == nil {
						consumed[sent.SHA256] = make(map[int]bool)
					}
					consumed[sent.SHA256][index] = true
					found = true
					break
				}
			}
			if !found {
				return errors.New("cookie phase injection not captured in its window")
			}
		}
		for _, response := range phase.Responses {
			values := byDigest[response.SHA256]
			if len(values) != 1 {
				return errors.New("cookie response identity is ambiguous")
			}
			at := values[0].at.Sub(time.Unix(0, report.StartNS)).Nanoseconds()
			if len(phase.Sent) == 0 || at < phase.Sent[0].AtNS || at > phase.AckNS {
				return errors.New("cookie response captured outside its phase")
			}
			decoded, err := cookieWireBody(profile, 3, 64, values[0].payload)
			if err != nil {
				return err
			}
			if binary.LittleEndian.Uint32(decoded[4:8]) != binary.LittleEndian.Uint32(body[4:8]) {
				return errors.New("cookie receiver differs from kernel sender")
			}
			var reply device.MessageCookieReply
			if err = binary.Read(bytes.NewReader(decoded), binary.LittleEndian, &reply); err != nil {
				return err
			}
			if !c.generator.ConsumeReply(&reply) {
				return errors.New("captured cookie authentication failed")
			}
		}
	}
	return nil
}
