package main

import (
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"testing"
	"time"
)

func TestAuthenticatedQualityBodyAndOverlappingHeadThroughReality(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer p.close()
	der, err := base64.StdEncoding.DecodeString(p.manifest.QualityCertificate)
	if err != nil {
		t.Fatal(err)
	}
	certificate, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	roots := x509.NewCertPool()
	roots.AddCert(certificate)
	for _, network := range []string{"tcp", "xhttp"} {
		for _, late := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/late-head=%t", network, late), func(t *testing.T) {
				client := realityHTTPClient(t, p, network, nil)
				client.Transport.(*http.Transport).TLSClientConfig = &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS13}
				before := p.count.Load()
				verifyQualityRound(t, p, client, late)
				if p.count.Load() != before+3 || p.directCount.Load() != 0 {
					t.Fatal("quality escaped owned REALITY route")
				}
			})
		}
	}
}

func verifyQualityRound(t *testing.T, p *peer, client *http.Client, late bool) {
	t.Helper()
	endpoint := "https://quality-a.example.test/quality-payload"
	baseline, err := client.Head(endpoint)
	if err != nil {
		t.Fatal(err)
	}
	baseline.Body.Close()
	if baseline.StatusCode != 200 || baseline.ContentLength != int64(qualityPayloadBytes) {
		t.Fatal("invalid baseline")
	}
	transfer, err := client.Get(endpoint)
	if err != nil {
		t.Fatal(err)
	}
	defer transfer.Body.Close()
	first := make([]byte, 1)
	if _, err := io.ReadFull(transfer.Body, first); err != nil {
		t.Fatal(err)
	}
	started := time.Now()
	type bodyResult struct {
		bytes []byte
		ended time.Time
		err   error
	}
	finished := make(chan bodyResult, 1)
	go func() {
		rest, readErr := io.ReadAll(transfer.Body)
		ended := time.Now()
		finished <- bodyResult{append(first, rest...), ended, readErr}
	}()
	// Deliberately issue the negative control only after independently observed EOF.
	var body bodyResult
	if late {
		body = <-finished
	}
	headStarted := time.Now()
	loaded, err := client.Head(endpoint)
	loadedAt := time.Now()
	if err != nil {
		t.Fatal(err)
	}
	loaded.Body.Close()
	if !late {
		body = <-finished
	}
	if body.err != nil {
		t.Fatal(body.err)
	}
	if len(body.bytes) != p.manifest.QualityBytes || fmt.Sprintf("%x", sha256.Sum256(body.bytes)) != p.manifest.QualitySHA256 {
		t.Fatal("body integrity failed")
	}
	if loaded.StatusCode != 200 || loaded.ContentLength != int64(qualityPayloadBytes) {
		t.Fatal("invalid loaded HEAD")
	}
	overlap := !headStarted.Before(started) && !loadedAt.After(body.ended)
	if overlap == late {
		t.Fatalf("observed overlap=%t, late control=%t", overlap, late)
	}
}

func TestOwnedQualityPortAllowsOnlyExactControlNames(t *testing.T) {
	p, err := startPeer(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer p.close()
	if p.manifest.QualityPort < 1 || p.manifest.QualityPort > 65535 {
		t.Fatal("invalid quality port")
	}
	der, err := base64.StdEncoding.DecodeString(p.manifest.QualityCertificate)
	if err != nil {
		t.Fatal(err)
	}
	certificate, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	roots := x509.NewCertPool()
	roots.AddCert(certificate)
	for _, network := range []string{"tcp", "xhttp"} {
		t.Run(network, func(t *testing.T) {
			before := completedQualityReceipts(p)
			client := realityHTTPClient(t, p, network, nil)
			client.Timeout = 3 * time.Second
			client.Transport.(*http.Transport).TLSClientConfig = &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS13}
			for _, host := range []string{"quality-a.example.test", "quality-b.example.test"} {
				response, err := client.Get(fmt.Sprintf("https://%s:%d/quality-payload", host, p.manifest.QualityPort))
				if err != nil {
					t.Fatal(err)
				}
				body, err := io.ReadAll(response.Body)
				response.Body.Close()
				if err != nil || response.StatusCode != 200 || len(body) != p.manifest.QualityBytes || fmt.Sprintf("%x", sha256.Sum256(body)) != p.manifest.QualitySHA256 {
					t.Fatal("owned control body failed")
				}
			}
			deadline := time.Now().Add(time.Second)
			for completedQualityReceipts(p) != before+2 {
				if time.Now().After(deadline) {
					t.Fatalf("completed controls=%d, before=%d", completedQualityReceipts(p), before)
				}
				time.Sleep(time.Millisecond)
			}
			for _, authority := range []string{
				fmt.Sprintf("sub.quality-a.example.test:%d", p.manifest.QualityPort),
				fmt.Sprintf("unrelated.example.test:%d", p.manifest.QualityPort),
				fmt.Sprintf("127.0.0.1:%d", p.manifest.QualityPort),
				fmt.Sprintf("quality-a.example.test:%d", 1+p.manifest.QualityPort%65535),
			} {
				response, err := client.Get("https://" + authority + "/quality-payload")
				if err == nil {
					response.Body.Close()
					t.Fatalf("unexpected control route: %s", authority)
				}
			}
			if p.directCount.Load() != 0 {
				t.Fatal("control escaped owned route")
			}
			if completedQualityReceipts(p) != before+2 {
				t.Fatal("unowned destination reached the quality origin")
			}
		})
	}
}

func completedQualityReceipts(p *peer) int {
	completed := 0
	for _, receipt := range p.requests.snapshot() {
		if receipt.Target == "QualityComplete" {
			completed++
		}
	}
	return completed
}
