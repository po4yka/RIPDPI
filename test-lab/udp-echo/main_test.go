package main

import (
	"bytes"
	"net"
	"testing"
	"time"
)

func TestServeEchoesDatagramUnchanged(t *testing.T) {
	server, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen server: %v", err)
	}
	defer server.Close()

	errs := make(chan error, 1)
	go func() {
		errs <- serve(server)
	}()

	client, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen client: %v", err)
	}
	defer client.Close()

	payload := []byte("ripdpi udp echo test")
	if _, err := client.WriteTo(payload, server.LocalAddr()); err != nil {
		t.Fatalf("write: %v", err)
	}

	if err := client.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("set deadline: %v", err)
	}
	buf := make([]byte, 1024)
	n, _, err := client.ReadFrom(buf)
	if err != nil {
		t.Fatalf("read echo: %v", err)
	}
	if !bytes.Equal(payload, buf[:n]) {
		t.Fatalf("echo mismatch: got %q want %q", buf[:n], payload)
	}

	if err := server.Close(); err != nil {
		t.Fatalf("close server: %v", err)
	}
	select {
	case err := <-errs:
		if err != nil {
			t.Fatalf("serve returned error: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("serve did not stop after close")
	}
}
