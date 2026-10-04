package main

import (
	"encoding/json"
	"strings"
	"sync"
	"testing"
)

func TestRequestReceiptsRetainOnlyBoundedWhitelistedEvidence(t *testing.T) {
	var receipts requestReceipts
	for range maxRequestReceipts + 3 {
		receipts.record("Direct", "/private-credential-and-endpoint")
	}
	receipts.record("Provider", "/owned-tcp")
	snapshot := receipts.snapshot()
	if len(snapshot) != maxRequestReceipts || snapshot[0].Sequence != 5 {
		t.Fatalf("unexpected bounded receipt window: %+v", snapshot)
	}
	last := snapshot[len(snapshot)-1]
	if last.Sequence != 68 || last.Label != "owned-tcp" || last.Target != "Provider" || last.ElapsedMs < 0 {
		t.Fatalf("unexpected last receipt: %+v", last)
	}
	encoded, err := json.Marshal(snapshot)
	if err != nil || strings.Contains(string(encoded), "private-credential") {
		t.Fatalf("unknown request material must not leave the handler: %s %v", encoded, err)
	}
	snapshot[0].Label = "mutated"
	if receipts.snapshot()[0].Label != "Unknown" {
		t.Fatal("a caller must not mutate retained evidence")
	}
}

func TestRequestReceiptsSerializeConcurrentHandlers(t *testing.T) {
	var receipts requestReceipts
	var handlers sync.WaitGroup
	for range 2 * maxRequestReceipts {
		handlers.Go(func() { receipts.record("Direct", "/wrong-identity-direct") })
	}
	handlers.Wait()
	snapshot := receipts.snapshot()
	for index, receipt := range snapshot {
		if receipt.Sequence != int64(maxRequestReceipts+index+1) || receipt.Label != "wrong-identity-direct" {
			t.Fatalf("lost or reordered handler evidence: %+v", snapshot)
		}
	}
}
