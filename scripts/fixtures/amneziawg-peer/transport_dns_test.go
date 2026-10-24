package main

import (
	"net"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func transportDNSQuery(t *testing.T, name string, kind dnsmessage.Type) []byte {
	t.Helper()
	message := dnsmessage.Message{
		Header: dnsmessage.Header{ID: 123, RecursionDesired: true},
		Questions: []dnsmessage.Question{{
			Name: dnsmessage.MustNewName(name), Type: kind, Class: dnsmessage.ClassINET,
		}},
	}
	packet, err := message.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return packet
}

func TestTransportDNSRefusesBackgroundAndRetainsOwnedReceipts(t *testing.T) {
	server, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	receipts := &httpDNSReceipts{}
	stopped := make(chan struct{})
	go func() {
		defer close(stopped)
		receipts.serve(server)
	}()
	stop := func() {
		_ = server.Close()
		select {
		case <-stopped:
		case <-time.After(time.Second):
			t.Fatal("DNS worker did not stop")
		}
	}
	t.Cleanup(stop)
	client, err := net.Dial("udp4", server.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	for _, query := range []struct {
		name string
		kind dnsmessage.Type
	}{
		{"background.invalid.", dnsmessage.TypeA},
		{"background.invalid.", dnsmessage.TypeAAAA},
		{"background.invalid.", dnsmessage.Type(65)},
		{"acceptance.test.", dnsmessage.TypeA},
		{"acceptance.test.", dnsmessage.TypeAAAA},
	} {
		if err = client.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
			t.Fatal(err)
		}
		if _, err = client.Write(transportDNSQuery(t, query.name, query.kind)); err != nil {
			t.Fatal(err)
		}
		buffer := make([]byte, 1024)
		size, err := client.Read(buffer)
		if err != nil {
			t.Fatalf("DNS response for %s/%s: %v", query.name, query.kind, err)
		}
		var answer dnsmessage.Message
		if err = answer.Unpack(buffer[:size]); err != nil {
			t.Fatal(err)
		}
		if !answer.Header.Response || answer.Header.ID != 123 || len(answer.Questions) != 1 ||
			answer.Questions[0].Name.String() != query.name || answer.Questions[0].Type != query.kind {
			t.Fatalf("response does not match query: %+v", answer)
		}
		if query.name != "acceptance.test." {
			if answer.Header.RCode != dnsmessage.RCodeRefused || len(answer.Answers) != 0 {
				t.Fatalf("background question was not refused: %+v", answer)
			}
			continue
		}
		if answer.Header.RCode != dnsmessage.RCodeSuccess || len(answer.Answers) != 1 || answer.Answers[0].Header.Type != query.kind {
			t.Fatalf("owned answer failed: %+v", answer)
		}
		switch resource := answer.Answers[0].Body.(type) {
		case *dnsmessage.AResource:
			if resource.A != [4]byte{192, 0, 2, 77} {
				t.Fatalf("wrong owned A answer: %v", resource.A)
			}
		case *dnsmessage.AAAAResource:
			if net.IP(resource.AAAA[:]).String() != "fd77::53" {
				t.Fatalf("wrong owned AAAA answer: %v", resource.AAAA)
			}
		default:
			t.Fatalf("unexpected answer type: %T", resource)
		}
	}
	stop()
	if receipts.errors != 0 || receipts.rejected != 3 || len(receipts.items) != 2 {
		t.Fatalf("background questions changed acceptance evidence: errors=%d rejected=%d receipts=%v", receipts.errors, receipts.rejected, receipts.items)
	}
	for index, kind := range []dnsmessage.Type{dnsmessage.TypeA, dnsmessage.TypeAAAA} {
		if receipts.items[index] != (httpDNSReceipt{"acceptance.test.", uint16(kind)}) {
			t.Fatalf("wrong acceptance receipt: %+v", receipts.items[index])
		}
	}
}

func TestTransportDNSRejectsMalformedAndInvalidOwnedQueries(t *testing.T) {
	valid := transportDNSQuery(t, "acceptance.test.", dnsmessage.TypeA)
	var base dnsmessage.Message
	if err := base.Unpack(valid); err != nil {
		t.Fatal(err)
	}
	pack := func(message dnsmessage.Message) []byte {
		t.Helper()
		packet, err := message.Pack()
		if err != nil {
			t.Fatal(err)
		}
		return packet
	}
	response, opcode, empty, multiple, wrongClass := base, base, base, base, base
	response.Header.Response = true
	opcode.Header.OpCode = 5
	empty.Questions = nil
	multiple.Questions = append([]dnsmessage.Question{base.Questions[0]}, base.Questions[0])
	wrongClass.Questions = []dnsmessage.Question{base.Questions[0]}
	wrongClass.Questions[0].Class = dnsmessage.ClassCHAOS
	for _, packet := range [][]byte{
		nil, {1, 2, 3}, valid[:len(valid)-1], pack(response), pack(opcode), pack(empty), pack(multiple), pack(wrongClass),
		transportDNSQuery(t, "acceptance.test.", dnsmessage.TypeTXT),
	} {
		if _, err := dnsAcceptanceResponse(packet); err == nil {
			t.Fatal("invalid query produced a DNS answer")
		}
	}
}

func TestTransportDNSDatagramErrorsRemainSeparateFromRejections(t *testing.T) {
	server, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	receipts := &httpDNSReceipts{}
	stopped := make(chan struct{})
	go func() {
		defer close(stopped)
		receipts.serve(server)
	}()
	t.Cleanup(func() {
		_ = server.Close()
		select {
		case <-stopped:
		case <-time.After(time.Second):
			t.Error("DNS worker did not stop")
		}
	})
	client, err := net.Dial("udp4", server.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	for index, packet := range [][]byte{{1, 2, 3}, transportDNSQuery(t, "acceptance.test.", dnsmessage.TypeTXT)} {
		if _, err = client.Write(packet); err != nil {
			t.Fatal(err)
		}
		deadline := time.Now().Add(time.Second)
		for {
			receipts.mu.Lock()
			errors, rejected, count := receipts.errors, receipts.rejected, len(receipts.items)
			receipts.mu.Unlock()
			if errors == index+1 && rejected == 0 && count == 0 {
				break
			}
			if time.Now().After(deadline) {
				t.Fatalf("invalid datagram not counted as error: errors=%d rejected=%d receipts=%d", errors, rejected, count)
			}
			time.Sleep(time.Millisecond)
		}
		if err = client.SetReadDeadline(time.Now().Add(20 * time.Millisecond)); err != nil {
			t.Fatal(err)
		}
		_, err = client.Read(make([]byte, 1024))
		if networkError, ok := err.(net.Error); !ok || !networkError.Timeout() {
			t.Fatalf("invalid datagram produced a response: %v", err)
		}
	}
}
