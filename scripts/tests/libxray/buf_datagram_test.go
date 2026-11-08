package buf_test

import (
	"bytes"
	"net"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/buf"
	xnet "github.com/xtls/xray-core/common/net"
)

func TestManagedByteStreamEmptyReadsTerminate(t *testing.T) {
	for _, metadata := range []bool{false, true} {
		packet := buf.New()
		defer packet.Release()
		if metadata {
			destination := xnet.UDPDestination(xnet.LocalHostIP, 53)
			packet.UDP = &destination
		} else if !(buf.MultiBuffer{packet}).IsEmpty() {
			t.Fatal("an empty byte buffer became a datagram")
		}
		var output bytes.Buffer
		writer := buf.NewBufferedWriter(buf.NewWriter(&output))
		completed := make(chan error, 1)
		go func() {
			if err := writer.WriteMultiBuffer(buf.MultiBuffer{packet}); err != nil {
				completed <- err
				return
			}
			completed <- writer.Close()
		}()
		select {
		case err := <-completed:
			if err != nil || output.Len() != 0 {
				t.Fatalf("empty stream changed: %v", err)
			}
		case <-time.After(time.Second):
			t.Fatal("the byte stream adapter spun on an empty datagram")
		}
	}
}

func TestManagedPacketReaderAndWriterPreserveEmptyUDP(t *testing.T) {
	input, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer input.Close()
	reader, err := net.Dial("udp4", input.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	reader.SetDeadline(time.Now().Add(time.Second))
	reader.Write([]byte("ready"))
	_, peer, err := input.ReadFrom(make([]byte, 32))
	if err != nil {
		t.Fatal(err)
	}
	collector, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer collector.Close()
	writer, err := net.Dial("udp4", collector.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer writer.Close()
	for _, payload := range [][]byte{nil, []byte("next"), nil} {
		input.WriteTo(payload, peer)
		packets, err := buf.NewPacketReader(reader).ReadMultiBuffer()
		if err != nil {
			t.Fatal(err)
		}
		if packets.IsEmpty() {
			buf.ReleaseMulti(packets)
			t.Fatal("a received datagram became an empty stream read")
		}
		if err := (&buf.SequentialWriter{Writer: writer}).WriteMultiBuffer(packets); err != nil {
			t.Fatal(err)
		}
		collector.SetReadDeadline(time.Now().Add(time.Second))
		response := make([]byte, 32)
		n, _, err := collector.ReadFrom(response)
		if err != nil || !bytes.Equal(response[:n], payload) {
			t.Fatalf("datagram boundary changed: %x %v", response[:n], err)
		}
	}
}
