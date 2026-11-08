package pipe_test

import (
	"io"
	"testing"
	"time"

	"github.com/xtls/xray-core/common/buf"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/pipe"
)

func TestManagedEmptyUDPConsumesPipeBudgetAndSurvivesClose(t *testing.T) {
	reader, writer := pipe.New(pipe.WithSizeLimit(0))
	defer reader.Interrupt()
	defer writer.Close()
	emptyDatagram := func() buf.MultiBuffer {
		packet := buf.New()
		destination := net.UDPDestination(net.LocalHostIP, 53)
		packet.UDP = &destination
		return buf.MultiBuffer{packet}
	}
	if err := writer.WriteMultiBuffer(emptyDatagram()); err != nil {
		t.Fatal(err)
	}
	pending := make(chan error, 1)
	go func() { pending <- writer.WriteMultiBuffer(emptyDatagram()) }()
	select {
	case <-pending:
		t.Fatal("empty UDP bypassed the byte budget")
	case <-time.After(20 * time.Millisecond):
	}
	first, err := reader.ReadMultiBuffer()
	if err != nil || len(first) != 1 || first[0].UDP == nil || first[0].Len() != 0 {
		t.Fatalf("first datagram changed: %v", err)
	}
	buf.ReleaseMulti(first)
	select {
	case err := <-pending:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("draining a datagram did not release the budget")
	}
	writer.Close()
	last, err := reader.ReadMultiBuffer()
	if err != nil || len(last) != 1 || last[0].UDP == nil || last[0].Len() != 0 {
		t.Fatalf("close discarded the final datagram: %v", err)
	}
	buf.ReleaseMulti(last)
	if _, err := reader.ReadMultiBuffer(); err != io.EOF {
		t.Fatalf("drained pipe did not terminate: %v", err)
	}
}
