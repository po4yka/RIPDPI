package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestTransportTransactionsCompletePayloadsAndRecordIndependentReceipts(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.close)
	control := httptest.NewServer(p.controlHandler())
	t.Cleanup(control.Close)
	for _, network := range []string{"tcp", "xhttp"} {
		t.Run(network, func(t *testing.T) {
			client := realityHTTPClient(t, p, network, nil)
			payload := make([]byte, 262144)
			for index := range payload {
				payload[index] = byte(index % 251)
			}
			digest := fmt.Sprintf("%x", sha256.Sum256(payload))
			id := network + "-download"
			response, err := client.Get("http://" + destination + "/transport/download?id=" + id + "&bytes=262144")
			if err != nil {
				t.Fatal(err)
			}
			body, err := io.ReadAll(response.Body)
			response.Body.Close()
			if err != nil || response.StatusCode != http.StatusOK || !bytes.Equal(payload, body) {
				t.Fatalf("complete download required: status=%d bytes=%d error=%v", response.StatusCode, len(body), err)
			}
			request, err := http.NewRequest(http.MethodPost, "http://"+destination+"/transport/upload?id="+network+"-upload", bytes.NewReader(payload))
			if err != nil {
				t.Fatal(err)
			}
			request.Header.Set("X-Content-SHA256", digest)
			response, err = client.Do(request)
			if err != nil {
				t.Fatal(err)
			}
			var upload map[string]any
			err = json.NewDecoder(response.Body).Decode(&upload)
			response.Body.Close()
			if err != nil || response.StatusCode != http.StatusOK || upload["sha256"] != digest || upload["bytes"] != float64(len(payload)) {
				t.Fatalf("complete upload required: status=%d receipt=%v error=%v", response.StatusCode, upload, err)
			}
			started := time.Now()
			response, err = client.Get("http://" + destination + "/transport/stream?id=" + network + "-stream&seconds=1")
			if err != nil {
				t.Fatal(err)
			}
			body, err = io.ReadAll(response.Body)
			response.Body.Close()
			if err != nil || response.StatusCode != http.StatusOK || len(body) != 131072 || time.Since(started) < time.Second {
				t.Fatalf("sustained stream incomplete: bytes=%d error=%v", len(body), err)
			}
		})
	}
	response, err := http.Get(control.URL + "/transport-receipts")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var receipts []map[string]any
	if err := json.NewDecoder(response.Body).Decode(&receipts); err != nil || len(receipts) != 6 {
		t.Fatalf("expected six independent completed receipts: count=%d error=%v", len(receipts), err)
	}
	if p.directCount.Load() != 0 {
		t.Fatal("transport transactions escaped to the direct sentinel")
	}
}

func TestTransportTransactionsRejectUnboundedOrCorruptRequests(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.close)
	client := realityHTTPClient(t, p, "tcp", nil)
	for _, path := range []string{
		"/transport/download?id=bounded-test&bytes=8388609",
		"/transport/download?id=bounded-test&bytes=-1",
		"/transport/stream?id=bounded-test&seconds=121",
		"/transport/download?id=bad/id&bytes=1",
	} {
		response, err := client.Get("http://" + destination + path)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode != http.StatusBadRequest {
			t.Fatalf("invalid transaction must fail: status=%d", response.StatusCode)
		}
	}
	request, err := http.NewRequest(http.MethodPost, "http://"+destination+"/transport/upload?id=corrupt-upload", bytes.NewReader([]byte("corrupt")))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("X-Content-SHA256", fmt.Sprintf("%064d", 0))
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusBadRequest {
		t.Fatal("corrupt upload must not produce a successful receipt")
	}
}
