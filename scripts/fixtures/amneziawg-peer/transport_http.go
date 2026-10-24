package main

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/signal"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"golang.org/x/net/dns/dnsmessage"
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

func (p *httpAcceptancePeer) serveTransportTransaction(w http.ResponseWriter, request *http.Request) {
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

func (p *httpAcceptancePeer) receiveTransportUpload(w http.ResponseWriter, request *http.Request, id string, started time.Time) {
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

// This is a destination inside the peer netstack, never an OS route or host listener.
type httpAcceptancePeer struct{ transfers transportReceipts }

func runHTTPAcceptance(network *netstack.Net, device *device.Device, bind *loopbackBind, endpoint string, publicKey string) error {
	listener, err := network.ListenTCPAddrPort(netip.MustParseAddrPort("192.0.2.77:80"))
	if err != nil {
		return err
	}
	defer listener.Close()
	receipts := &httpAcceptancePeer{}
	dns := &httpDNSReceipts{}
	for _, address := range []string{"10.77.0.1:53", "[fd77::1]:53"} {
		socket, err := network.ListenUDPAddrPort(netip.MustParseAddrPort(address))
		if err != nil {
			return err
		}
		defer socket.Close()
		go dns.serve(socket)
	}
	destination := &http.Server{Handler: http.HandlerFunc(receipts.serveTransportTransaction), ReadHeaderTimeout: 3 * time.Second, MaxHeaderBytes: 16384}
	defer destination.Close()
	go func() { _ = destination.Serve(listener) }()
	control, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer control.Close()
	finish := make(chan error, 1)
	var transitions sync.Mutex
	running := true
	mux := http.NewServeMux()
	mux.HandleFunc("GET /manifest", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"endpoint": endpoint, "public_key": publicKey, "controlPort": control.Addr().(*net.TCPAddr).Port, "mode": "http-tun", "destination": "192.0.2.77:80"})
	})
	mux.HandleFunc("GET /transport-receipts", func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(receipts.transfers.snapshot())
	})
	mux.HandleFunc("GET /dns-receipts", func(w http.ResponseWriter, _ *http.Request) {
		dns.mu.Lock()
		defer dns.mu.Unlock()
		_ = json.NewEncoder(w).Encode(map[string]any{"receipts": dns.items, "errors": dns.errors, "rejected": dns.rejected})
	})
	for route, start := range map[string]bool{"/peer/stop": false, "/peer/start": true} {
		mux.HandleFunc("POST "+route, func(w http.ResponseWriter, _ *http.Request) {
			transitions.Lock()
			defer transitions.Unlock()
			var err error
			if start != running {
				if start {
					err = device.Up()
					if err == nil {
						actual, addressErr := bind.address()
						if addressErr != nil || actual != endpoint {
							err = fmt.Errorf("endpoint changed after recovery")
						}
					}
				} else {
					err = device.Down()
				}
			}
			if err != nil {
				http.Error(w, "transition failed", 500)
				return
			}
			running = start
			_ = json.NewEncoder(w).Encode(map[string]bool{"running": running})
		})
	}
	mux.HandleFunc("POST /finish", func(w http.ResponseWriter, _ *http.Request) {
		transitions.Lock()
		defer transitions.Unlock()
		err := bind.observation.finish(2)
		if err != nil {
			http.Error(w, "wire acceptance failed", 500)
		} else {
			_ = json.NewEncoder(w).Encode(map[string]bool{"wireAccepted": true})
		}
		select {
		case finish <- err:
		default:
		}
	})
	server := &http.Server{Handler: mux, ReadHeaderTimeout: 3 * time.Second, WriteTimeout: 5 * time.Second, MaxHeaderBytes: 16384}
	defer server.Close()
	go func() { _ = server.Serve(control) }()
	if err := json.NewEncoder(os.Stdout).Encode(map[string]any{"endpoint": endpoint, "public_key": publicKey, "controlPort": control.Addr().(*net.TCPAddr).Port, "mode": "http-tun"}); err != nil {
		return err
	}
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT)
	defer signal.Stop(signals)
	timer := time.NewTimer(10 * time.Minute)
	defer timer.Stop()
	select {
	case err := <-finish:
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if shutdownErr := server.Shutdown(ctx); shutdownErr != nil {
			return shutdownErr
		}
		return err
	case <-signals:
		return fmt.Errorf("HTTP acceptance interrupted before wire receipt")
	case <-timer.C:
		return fmt.Errorf("HTTP acceptance deadline exceeded")
	}
}

type httpDNSReceipt struct {
	Query string `json:"query"`
	Type  uint16 `json:"type"`
}
type httpDNSReceipts struct {
	mu       sync.Mutex
	items    []httpDNSReceipt
	errors   int
	rejected int
}

func (r *httpDNSReceipts) serve(socket net.PacketConn) {
	buffer := make([]byte, 1024)
	for {
		size, source, err := socket.ReadFrom(buffer)
		if err != nil {
			return
		}
		query, err := dnsAcceptanceResponse(buffer[:size])
		if err == nil {
			var sent int
			sent, err = socket.WriteTo(query.body, source)
			if err == nil && sent != len(query.body) {
				err = io.ErrShortWrite
			}
		}
		r.mu.Lock()
		if err != nil {
			r.errors++
		} else if query.owned {
			r.items = append(r.items, httpDNSReceipt{"acceptance.test.", uint16(query.kind)})
			if len(r.items) > 128 {
				r.items = append([]httpDNSReceipt{}, r.items[len(r.items)-128:]...)
			}
		} else {
			r.rejected++
		}
		r.mu.Unlock()
	}
}

type httpDNSAnswer struct {
	body  []byte
	kind  dnsmessage.Type
	owned bool
}

func dnsAcceptanceResponse(packet []byte) (httpDNSAnswer, error) {
	var message dnsmessage.Message
	if err := message.Unpack(packet); err != nil {
		return httpDNSAnswer{}, err
	}
	header := message.Header
	if header.Response || header.OpCode != 0 || header.RCode != dnsmessage.RCodeSuccess ||
		header.Truncated || len(message.Questions) != 1 || len(message.Answers) != 0 || len(message.Authorities) != 0 ||
		message.Questions[0].Class != dnsmessage.ClassINET {
		return httpDNSAnswer{}, fmt.Errorf("invalid acceptance DNS question")
	}
	question := message.Questions[0]
	owned := question.Name.String() == "acceptance.test."
	code := dnsmessage.RCodeSuccess
	if !owned {
		code = dnsmessage.RCodeRefused
	}
	builder := dnsmessage.NewBuilder(nil, dnsmessage.Header{
		ID: header.ID, Response: true, Authoritative: owned, RecursionDesired: header.RecursionDesired, RCode: code,
	})
	if err := builder.StartQuestions(); err != nil {
		return httpDNSAnswer{}, err
	}
	if err := builder.Question(question); err != nil {
		return httpDNSAnswer{}, err
	}
	if !owned {
		body, err := builder.Finish()
		return httpDNSAnswer{body: body, kind: question.Type}, err
	}
	if err := builder.StartAnswers(); err != nil {
		return httpDNSAnswer{}, err
	}
	record := dnsmessage.ResourceHeader{Name: question.Name, Class: dnsmessage.ClassINET, TTL: 1}
	var err error
	switch question.Type {
	case dnsmessage.TypeA:
		err = builder.AResource(record, dnsmessage.AResource{A: [4]byte{192, 0, 2, 77}})
	case dnsmessage.TypeAAAA:
		err = builder.AAAAResource(record, dnsmessage.AAAAResource{AAAA: netip.MustParseAddr("fd77::53").As16()})
	default:
		return httpDNSAnswer{}, fmt.Errorf("unsupported acceptance DNS type")
	}
	if err != nil {
		return httpDNSAnswer{}, err
	}
	body, err := builder.Finish()
	return httpDNSAnswer{body: body, kind: question.Type, owned: true}, err
}
