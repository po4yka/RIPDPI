package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestNginxTLSXHTTPCompletesAuthenticatedPayloadAndRecovers(t *testing.T) {
	directory := t.TempDir()
	tlsDirectory := filepath.Join(directory, "tls")
	p, err := startPeerWithOptions(t.Context(), peerOptions{
		BindHost: "127.0.0.1", AdvertiseHost: "10.0.2.2", TLSDirectory: tlsDirectory,
		ClientID: "550e8400-e29b-41d4-a716-446655440003",
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.close)
	port := startNginxForTest(t, directory, tlsDirectory, p.manifest.TLSBackendPort)
	certificate, err := os.ReadFile(filepath.Join(tlsDirectory, "certificate.pem"))
	if err != nil {
		t.Fatal(err)
	}
	settings := func() map[string]any {
		return map[string]any{
			"serverName": serverName, "fingerprint": "chrome", "allowInsecure": false, "disableSystemRoot": true,
			"certificates": []any{map[string]any{"usage": "verify", "certificate": strings.Split(strings.TrimSpace(string(certificate)), "\n")}},
		}
	}
	client := configuredHTTPClient(t, p, "xhttp", map[string]any{"maxConnections": 1}, settings(), port, p.manifest.UUID)
	checkPayload := func(label string) {
		t.Helper()
		response, err := client.Get("http://" + destination + "/transport/download?id=" + label + "&bytes=262144")
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		body, err := io.ReadAll(response.Body)
		if err != nil || response.StatusCode != 200 || !bytes.Equal(body, transportPayload(262144)) {
			t.Fatalf("nginx TLS XHTTP payload incomplete: status=%d bytes=%d error=%v", response.StatusCode, len(body), err)
		}
	}
	checkPayload("nginx-baseline")
	for _, failure := range []string{"identity", "trust", "name"} {
		t.Run(failure, func(t *testing.T) {
			negative := settings()
			identity := p.manifest.UUID
			switch failure {
			case "identity":
				identity = "00000000-0000-4000-8000-000000000001"
			case "trust":
				negative["certificates"] = []any{}
			case "name":
				negative["serverName"] = "wrong.test"
			}
			denied := configuredHTTPClient(t, p, "xhttp", nil, negative, port, identity)
			before := len(p.transfers.snapshot())
			response, err := denied.Get("http://" + destination + "/transport/download?id=nginx-negative&bytes=262144")
			if err == nil {
				response.Body.Close()
				t.Fatal("invalid nginx TLS XHTTP authority reached the destination")
			}
			if len(p.transfers.snapshot()) != before {
				t.Fatal("rejected nginx client created a destination receipt")
			}
		})
	}
	if err := p.setRunning(false); err != nil {
		t.Fatal(err)
	}
	response, err := client.Get("http://" + destination + "/transport/download?id=nginx-stopped&bytes=262144")
	if err == nil {
		response.Body.Close()
		t.Fatal("stopped peer still accepted pooled nginx XHTTP traffic")
	}
	if err := p.setRunning(true); err != nil {
		t.Fatal(err)
	}
	checkPayload("nginx-recovered")
	if len(p.transfers.snapshot()) != 2 || p.directCount.Load() != 0 {
		t.Fatal("nginx recovery has stale or direct receipts")
	}
}

func startNginxForTest(t *testing.T, directory, tlsDirectory string, upstreamPort int) int {
	t.Helper()
	ready := filepath.Join(directory, "nginx-ready.json")
	log, err := os.Create(filepath.Join(directory, "nginx-controller.log"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { log.Close() })
	command := exec.Command("python3", "../../ci/android_nginx_fixture.py",
		"--directory", filepath.Join(directory, "nginx"), "--tls-directory", tlsDirectory,
		"--upstream-port", fmt.Sprint(upstreamPort), "--ready-file", ready)
	command.Stdout, command.Stderr = log, log
	if err := command.Start(); err != nil {
		t.Fatal(err)
	}
	finished := make(chan error, 1)
	go func() { finished <- command.Wait() }()
	t.Cleanup(func() {
		_ = command.Process.Signal(syscall.SIGTERM)
		select {
		case err := <-finished:
			if err != nil {
				t.Errorf("nginx controller did not confirm cleanup: %v", err)
			}
		case <-time.After(35 * time.Second):
			_ = command.Process.Kill()
			t.Error("nginx controller cleanup timed out")
		}
	})
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		data, err := os.ReadFile(ready)
		var result struct {
			Port int `json:"port"`
		}
		if err == nil && json.Unmarshal(data, &result) == nil && result.Port > 0 {
			return result.Port
		}
		time.Sleep(50 * time.Millisecond)
	}
	data, _ := os.ReadFile(log.Name())
	nginxLog, _ := os.ReadFile(filepath.Join(directory, "nginx", "nginx.log"))
	t.Fatalf("nginx fixture readiness failed: %s\n%s", data, nginxLog)
	return 0
}
