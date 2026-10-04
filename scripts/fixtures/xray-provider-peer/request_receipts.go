package main

import (
	"sync"
	"time"
)

const maxRequestReceipts = 64

type requestReceipt struct {
	Sequence  int64  `json:"sequence"`
	ElapsedMs int64  `json:"elapsedMs"`
	Target    string `json:"target"`
	Label     string `json:"label"`
}

// Only fixed fixture labels leave the HTTP handler; no URL, address or header is retained.
type requestReceipts struct {
	mu        sync.Mutex
	startedAt time.Time
	sequence  int64
	items     []requestReceipt
}

func (r *requestReceipts) record(target, path string) {
	label := "Unknown"
	switch path {
	case "/before-tcp", "/before-xhttp", "/after-tcp", "/after-xhttp",
		"/wrong-identity", "/wrong-identity-direct", "/owned-tcp", "/owned-xhttp":
		label = path[1:]
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.startedAt.IsZero() {
		r.startedAt = time.Now()
	}
	r.sequence++
	r.items = append(r.items, requestReceipt{r.sequence, time.Since(r.startedAt).Milliseconds(), target, label})
	if len(r.items) > maxRequestReceipts {
		copy(r.items, r.items[len(r.items)-maxRequestReceipts:])
		r.items = r.items[:maxRequestReceipts]
	}
}

func (r *requestReceipts) snapshot() []requestReceipt {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]requestReceipt{}, r.items...)
}
