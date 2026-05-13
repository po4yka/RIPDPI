package main

import (
	"bufio"
	"encoding/json"
	"net"
	"testing"
	"time"
)

func TestHandleAcceptsValidHandshake(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()

	go handle(server, "ok")
	_, _ = client.Write([]byte(`{"auth":"ok"}` + "\n"))

	var got response
	if err := json.NewDecoder(bufio.NewReader(client)).Decode(&got); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if !got.OK || got.Code != "READY" {
		t.Fatalf("unexpected response: %+v", got)
	}
}

func TestHandleRejectsAuthFailure(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()

	go handle(server, "auth_fail")
	_, _ = client.Write([]byte(`{"auth":"ok"}` + "\n"))

	var got response
	if err := json.NewDecoder(bufio.NewReader(client)).Decode(&got); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if got.OK || got.Code != "AUTH_FAILED" {
		t.Fatalf("unexpected response: %+v", got)
	}
}

func TestHandleMalformedModeWritesInvalidJson(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()

	go handle(server, "malformed")
	_ = client.SetReadDeadline(time.Now().Add(2 * time.Second))

	line, err := bufio.NewReader(client).ReadString('\n')
	if err != nil {
		t.Fatalf("read response: %v", err)
	}
	if line != "not-json\n" {
		t.Fatalf("unexpected malformed response: %q", line)
	}
}
