package main

import (
	"encoding/binary"
	"fmt"
	"testing"
	"time"
)

// These are structural datagrams for the observer, not authenticated peer traffic.
func wirePacket(size, padding int, header uint32) []byte {
	packet := make([]byte, size)
	binary.LittleEndian.PutUint32(packet[padding:padding+4], header)
	return packet
}

func transportWirePacket(size int, header uint32, counter uint64) []byte {
	packet := wirePacket(size, 0, header)
	binary.LittleEndian.PutUint32(packet[4:8], 1)
	binary.LittleEndian.PutUint64(packet[8:16], counter)
	return packet
}

func completeWirePrelude(observation *wireObservation, sequence byte) {
	signature := make([]byte, 38)
	copy(signature, []byte{0xf0, 0xf1})
	for i := 2; i < 18; i++ {
		signature[i] = sequence
	}
	copy(signature[18:26], []byte("01234567"))
	copy(signature[26:34], []byte("aBcDeFgH"))
	binary.BigEndian.PutUint32(signature[34:38], uint32(time.Now().Unix()))
	observation.observe(signature, true)
	for _, marker := range []byte{0xf2, 0xf3, 0xf4, 0xf5} {
		observation.observe([]byte{marker}, true)
	}
	for i := 0; i < 4; i++ {
		observation.observe(make([]byte, 64+i), true)
	}
}

func TestH4CounterCannotBecomeInitiation(t *testing.T) {
	for _, counter := range []uint64{100, 199} {
		for _, inbound := range []bool{true, false} {
			t.Run(fmt.Sprintf("counter%d-inbound%t", counter, inbound), func(t *testing.T) {
				var observation wireObservation
				observation.observe(transportWirePacket(32, 400, counter), inbound)
				actual := observation.outbound
				if inbound {
					actual = observation.inbound
				}
				if observation.failure != "" || observation.handshakes != 0 || !actual[3][400] || len(actual[0]) != 0 {
					t.Fatalf("transport counter became header: failure=%q handshakes=%d H1=%v H4=%v", observation.failure, observation.handshakes, actual[0], actual[3])
				}
			})
		}
	}
}

func TestWireInitiationStillRequiresCompletePrelude(t *testing.T) {
	for _, complete := range []bool{false, true} {
		t.Run(fmt.Sprint(complete), func(t *testing.T) {
			var observation wireObservation
			if complete {
				completeWirePrelude(&observation, 1)
			}
			packet := wirePacket(156, 8, 100)
			// Padding can resemble an H4 header; the real padded H1 still takes precedence.
			binary.LittleEndian.PutUint32(packet[:4], 400)
			observation.observe(packet, true)
			if observation.handshakes != 1 || !observation.inbound[0][100] || observation.stage != 0 {
				t.Fatal("true initiation not recorded")
			}
			if complete && observation.failure != "" {
				t.Fatal(observation.failure)
			}
			if !complete && observation.failure != "initiation arrived without complete I/J prelude" {
				t.Fatal("missing prelude was accepted")
			}
		})
	}
}

func TestWireHeaderClassificationRequiresPacketGeometry(t *testing.T) {
	for _, spec := range []struct {
		size, padding, index int
		header               uint32
	}{{156, 8, 0, 100}, {104, 12, 1, 200}, {64, 0, 2, 300}, {32, 0, 3, 400}} {
		for _, delta := range []int{-1, 0, 1} {
			t.Run(fmt.Sprintf("H%d-size%d", spec.index+1, spec.size+delta), func(t *testing.T) {
				var observation wireObservation
				// Outbound classification has no client prelude state.
				observation.observe(wirePacket(spec.size+delta, spec.padding, spec.header), false)
				valid := delta == 0 || spec.index == 3 && delta == 1
				if observation.outbound[spec.index][spec.header] != valid {
					t.Fatal("packet size did not constrain header classification")
				}
			})
		}
	}
	var observation wireObservation
	observation.observe(wirePacket(31, 0, 400), true)
	if observation.failure != "packet outside configured header ranges" {
		t.Fatal("truncated transport packet was accepted")
	}
}

func TestWireTransportCountersDoNotChangeHandshakeState(t *testing.T) {
	var observation wireObservation
	completeWirePrelude(&observation, 1)
	observation.observe(wirePacket(156, 8, 100), true)
	for counter := uint64(0); counter < 512; counter++ {
		header := uint32(400 + counter%100)
		size := []int{32, 128, 1452}[counter%3]
		packet := transportWirePacket(size, header, counter)
		observation.observe(packet, true)
		observation.observe(packet, false)
	}
	completeWirePrelude(&observation, 2)
	observation.observe(wirePacket(156, 8, 199), true)
	if observation.failure != "" || observation.handshakes != 2 || observation.stage != 0 || len(observation.signatures) != 2 {
		t.Fatalf("transport changed handshake state: failure=%q handshakes=%d stage=%d signatures=%d", observation.failure, observation.handshakes, observation.stage, len(observation.signatures))
	}
	for _, headers := range [][4]map[uint32]bool{observation.inbound, observation.outbound} {
		if len(headers[3]) != 100 {
			t.Fatal("transport header variation was lost")
		}
	}
	if len(observation.inbound[0]) != 2 || len(observation.outbound[0]) != 0 {
		t.Fatal("transport became initiation")
	}
}
