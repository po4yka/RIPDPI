package main

import (
	"bytes"
	"encoding/binary"
	"testing"
	"time"

	"golang.org/x/crypto/chacha20"
)

func TestKernelNegativeProofRejectsMissingMutationsAndRecovery(t *testing.T) {
	report := kernelRelayReport{Port: 50000, ForeignPort: 50001, StartNS: time.Second.Nanoseconds(), EndNS: (10 * time.Second).Nanoseconds()}
	var packets []capturedUDP
	ip := [4]byte{127, 0, 0, 1}
	for index, mode := range transportModes {
		at := time.Unix(int64(index+2), 0)
		original := bytes.Repeat([]byte{byte(index + 1)}, 356)
		binary.LittleEndian.PutUint32(original[24:], 450^headerMask(amneziaVPN5xKey, original))
		modified := append([]byte(nil), original...)
		if mode == "corrupt" {
			modified[355] ^= 1
		}
		if mode == "wrong-hp" {
			for _, key := range [][]byte{amneziaVPN5xKey, bytes.Repeat([]byte{4}, 32)} {
				cipher, _ := chacha20.NewUnauthenticatedCipher(key, modified[:12])
				cipher.XORKeyStream(modified[24:40], modified[24:40])
			}
		}
		if mode == "ambiguous" {
			for i, offset := range []int{12, 20, 16} {
				binary.LittleEndian.PutUint32(modified[offset:], uint32((i+1)*101)^headerMask(amneziaVPN5xKey, modified))
			}
		}
		phase := transportPhase{Mode: mode, OriginalSHA256: wireDigest(original), OriginalBodySHA256: wireDigest(original[24:]), OriginalCipherSHA256: wireDigest(original[40:]), WireBodySHA256: wireDigest(modified[24:]), WireCipherSHA256: wireDigest(modified[40:])}
		report.Actions = append(report.Actions, relayAction{"out", wireDigest(original), false})
		packets = append(packets, capturedUDP{at: at, source: 51820, destination: 50000, sourceIP: ip, destinationIP: ip, payload: original})
		count := 1
		if mode == "replay" {
			count = 2
		}
		port := uint16(50000)
		if mode == "foreign-source" {
			port = 50001
		}
		for range count {
			phase.WireSHA256 = append(phase.WireSHA256, wireDigest(modified))
			packets = append(packets, capturedUDP{at: at.Add(time.Millisecond), source: port, destination: 40000, sourceIP: ip, destinationIP: ip, payload: modified})
		}
		if index < 3 {
			restored := at.Add(100 * time.Millisecond)
			phase.RestoredNS = restored.UnixNano() - report.StartNS
			packets = append(packets, capturedUDP{at: restored, source: 50000, destination: 40000, sourceIP: ip, destinationIP: ip, payload: original})
		}
		report.Negative = append(report.Negative, phase)
	}
	if _, err := checkKernelNegativeWire(report, packets); err != nil {
		t.Fatal(err)
	}
	for index := range packets {
		changed := append([]capturedUDP(nil), packets[:index]...)
		changed = append(changed, packets[index+1:]...)
		if _, err := checkKernelNegativeWire(report, changed); err == nil {
			t.Fatal("missing kernel/client record accepted", index)
		}
	}
	changed := append([]capturedUDP(nil), packets...)
	changed[1].source = 50001
	if _, err := checkKernelNegativeWire(report, changed); err == nil {
		t.Fatal("unexpected carrier source accepted")
	}
	changed = append([]capturedUDP(nil), packets...)
	changed[1].destination++
	if _, err := checkKernelNegativeWire(report, changed); err == nil {
		t.Fatal("changed client port accepted")
	}
	report.Negative[0].WireCipherSHA256 = report.Negative[0].OriginalCipherSHA256
	if _, err := checkKernelNegativeWire(report, packets); err == nil {
		t.Fatal("missing ciphertext mutation accepted")
	}
}
