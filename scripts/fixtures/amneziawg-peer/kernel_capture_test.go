package main

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func smallCapture(order binary.ByteOrder, link uint32, nanoseconds bool) []byte {
	var out bytes.Buffer
	magic := uint32(0xa1b2c3d4)
	if nanoseconds {
		magic = 0xa1b23c4d
	}
	binary.Write(&out, order, magic)
	binary.Write(&out, order, uint16(2))
	binary.Write(&out, order, uint16(4))
	for _, value := range []uint32{0, 0, 65535, link} {
		binary.Write(&out, order, value)
	}
	prefix := map[uint32]int{1: 14, 113: 16, 276: 20, 101: 0}[link]
	packet := make([]byte, prefix+45)
	switch link {
	case 1:
		binary.BigEndian.PutUint16(packet[12:], 0x800)
	case 113:
		binary.BigEndian.PutUint16(packet[14:], 0x800)
	case 276:
		binary.BigEndian.PutUint16(packet, 0x800)
	}
	ip := packet[prefix:]
	ip[0] = 0x45
	ip[9] = 17
	binary.BigEndian.PutUint16(ip[2:], 45)
	copy(ip[12:16], []byte{10, 77, 0, 2})
	copy(ip[16:20], []byte{10, 77, 0, 1})
	binary.BigEndian.PutUint16(ip[20:], 50001)
	binary.BigEndian.PutUint16(ip[22:], 41002)
	binary.BigEndian.PutUint16(ip[24:], 25)
	copy(ip[28:], []byte("awg-padding-check"))
	for _, value := range []uint32{100, 200, uint32(len(packet)), uint32(len(packet))} {
		binary.Write(&out, order, value)
	}
	out.Write(packet)
	return out.Bytes()
}
func TestKernelCaptureDecodesSupportedLinksAndClockUnits(t *testing.T) {
	for _, order := range []binary.ByteOrder{binary.LittleEndian, binary.BigEndian} {
		for _, link := range []uint32{1, 113, 276, 101} {
			for _, nano := range []bool{false, true} {
				var packets []capturedUDP
				summary, err := readUDPCapture(bytes.NewReader(smallCapture(order, link, nano)), func(packet capturedUDP) error { packets = append(packets, packet); return nil })
				if err != nil {
					t.Fatal(err)
				}
				wantNanos := 200000
				if nano {
					wantNanos = 200
				}
				if summary.Packets != 1 || summary.LinkType != link || summary.Nanoseconds != nano || len(packets) != 1 {
					t.Fatal("capture metadata differs")
				}
				packet := packets[0]
				if packet.at.Unix() != 100 || packet.at.Nanosecond() != wantNanos || packet.ipLength != 45 || packet.source != 50001 || packet.destination != 41002 || string(packet.payload) != "awg-padding-check" {
					t.Fatal("captured IP/UDP or timestamp differs")
				}
			}
		}
	}
}
func TestKernelCaptureRejectsUnsupportedOrTruncatedInput(t *testing.T) {
	good := smallCapture(binary.LittleEndian, 1, false)
	cases := [][]byte{good[:10], good[:30], good[:len(good)-1]}
	for _, mutate := range []func([]byte){
		func(b []byte) { b[0] = 0 },
		func(b []byte) { binary.LittleEndian.PutUint32(b[20:], 999) },
		func(b []byte) { binary.LittleEndian.PutUint32(b[32:], 1) },
		func(b []byte) { binary.LittleEndian.PutUint32(b[28:], 1000000) },
		func(b []byte) { b[54+6] = 0x20 },
		func(b []byte) { b[54+9] = 6 },
		func(b []byte) { binary.BigEndian.PutUint16(b[54+24:], 24) },
	} {
		changed := append([]byte(nil), good...)
		mutate(changed)
		cases = append(cases, changed)
	}
	for _, input := range cases {
		if _, err := readUDPCapture(bytes.NewReader(input), func(capturedUDP) error { return nil }); err == nil {
			t.Fatal("invalid capture accepted")
		}
	}
}
