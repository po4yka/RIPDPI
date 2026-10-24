package main

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const maxTransportBytes = 8 * 1024 * 1024
const transportChunkBytes = 65536

var transactionID = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{7,63}$`)
var transactionDigest = regexp.MustCompile(`^[a-f0-9]{64}$`)

type transportReceipt struct {
	ID        string `json:"id"`
	Operation string `json:"operation"`
	Bytes     int    `json:"bytes"`
	SHA256    string `json:"sha256"`
	ElapsedMs int64  `json:"elapsedMs"`
}

type transportReceipts struct {
	mu    sync.Mutex
	items []transportReceipt
}

func (r *transportReceipts) record(receipt transportReceipt) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.items = append(r.items, receipt)
	if len(r.items) > 128 {
		r.items = append([]transportReceipt{}, r.items[len(r.items)-128:]...)
	}
}

func (r *transportReceipts) snapshot() []transportReceipt {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]transportReceipt{}, r.items...)
}

func transportPayload(size int) []byte {
	payload := make([]byte, size)
	for index := range payload {
		payload[index] = byte(index % 251)
	}
	return payload
}

func (p *peer) serveTransportTransaction(w http.ResponseWriter, request *http.Request) {
	started := time.Now()
	id := request.URL.Query().Get("id")
	if !transactionID.MatchString(id) {
		http.Error(w, "invalid transaction", http.StatusBadRequest)
		return
	}
	operation := strings.TrimPrefix(request.URL.Path, "/transport/")
	if operation == "upload" && request.Method == http.MethodPost {
		p.receiveTransportUpload(w, request, id, started)
		return
	}
	if request.Method != http.MethodGet || (operation != "download" && operation != "stream") {
		http.Error(w, "unsupported transaction", http.StatusBadRequest)
		return
	}
	size, err := strconv.Atoi(request.URL.Query().Get("bytes"))
	duration := 0
	if operation == "stream" {
		duration, err = strconv.Atoi(request.URL.Query().Get("seconds"))
		if duration < 1 || duration > 120 {
			http.Error(w, "invalid stream duration", http.StatusBadRequest)
			return
		}
		size = (duration + 1) * transportChunkBytes
	}
	if err != nil || size < 1 || size > maxTransportBytes {
		http.Error(w, "invalid payload length", http.StatusBadRequest)
		return
	}
	payload := transportPayload(size)
	digest := fmt.Sprintf("%x", sha256.Sum256(payload))
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(size))
	w.Header().Set("X-Content-SHA256", digest)
	w.Header().Set("Connection", "close")
	w.WriteHeader(http.StatusOK)
	for offset := 0; offset < size; offset += transportChunkBytes {
		if duration > 0 && offset > 0 {
			timer := time.NewTimer(time.Until(started.Add(time.Duration(offset/transportChunkBytes) * time.Second)))
			select {
			case <-request.Context().Done():
				timer.Stop()
				return
			case <-timer.C:
			}
		}
		chunk := payload[offset:min(size, offset+transportChunkBytes)]
		if written, err := w.Write(chunk); err != nil || written != len(chunk) {
			return
		}
		if err := http.NewResponseController(w).Flush(); err != nil {
			return
		}
	}
	p.transfers.record(transportReceipt{id, operation, size, digest, time.Since(started).Milliseconds()})
}

func (p *peer) receiveTransportUpload(w http.ResponseWriter, request *http.Request, id string, started time.Time) {
	expected := request.Header.Get("X-Content-SHA256")
	if request.ContentLength < 1 || request.ContentLength > maxTransportBytes || !transactionDigest.MatchString(expected) {
		http.Error(w, "invalid upload bounds", http.StatusBadRequest)
		return
	}
	digest := sha256.New()
	length, err := io.Copy(digest, io.LimitReader(request.Body, request.ContentLength+1))
	actual := fmt.Sprintf("%x", digest.Sum(nil))
	if err != nil || length != request.ContentLength || actual != expected {
		http.Error(w, "incomplete upload", http.StatusBadRequest)
		return
	}
	receipt := transportReceipt{id, "upload", int(length), actual, time.Since(started).Milliseconds()}
	p.transfers.record(receipt)
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Connection", "close")
	_ = json.NewEncoder(w).Encode(receipt)
}
