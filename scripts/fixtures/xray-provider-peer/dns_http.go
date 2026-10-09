package main

import (
	"encoding/base64"
	"encoding/binary"
	"errors"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

const maxDNSHTTPMessage = 65535

func (p *peer) startOwnedDNSHTTP() (net.Listener, error) {
	host := p.options.BindHost
	if host == "" {
		host = "127.0.0.1"
	}
	listener, err := net.Listen("tcp4", net.JoinHostPort(host, "0"))
	if err != nil {
		return nil, err
	}
	server := &http.Server{
		Handler:           http.HandlerFunc(p.serveDNSHTTP),
		ReadHeaderTimeout: 3 * time.Second,
		ReadTimeout:       5 * time.Second,
		WriteTimeout:      5 * time.Second,
		IdleTimeout:       5 * time.Second,
		// GET carries a base64url message in the request line.
		MaxHeaderBytes: 96 * 1024,
	}
	// Own the listener even if close races with the Serve goroutine starting.
	p.closers = append(p.closers, listener, server)
	go func() { _ = server.Serve(listener) }()
	return listener, nil
}

func (p *peer) serveDNSHTTP(w http.ResponseWriter, request *http.Request) {
	if request.URL.Path != "/dns-query" {
		http.NotFound(w, request)
		return
	}
	var packet []byte
	switch request.Method {
	case http.MethodPost:
		contentType, _, err := mime.ParseMediaType(request.Header.Get("Content-Type"))
		if err != nil || contentType != "application/dns-message" {
			http.Error(w, "expected application/dns-message", http.StatusUnsupportedMediaType)
			return
		}
		request.Body = http.MaxBytesReader(w, request.Body, maxDNSHTTPMessage)
		packet, err = io.ReadAll(request.Body)
		if err != nil {
			var oversized *http.MaxBytesError
			status := http.StatusBadRequest
			if errors.As(err, &oversized) {
				status = http.StatusRequestEntityTooLarge
			}
			http.Error(w, "invalid DNS body", status)
			return
		}
	case http.MethodGet:
		parameters, err := url.ParseQuery(request.URL.RawQuery)
		values := parameters["dns"]
		if err != nil || len(values) != 1 || values[0] == "" || strings.ContainsAny(values[0], "\r\n") {
			http.Error(w, "expected one base64url DNS query", http.StatusBadRequest)
			return
		}
		if len(values[0]) > base64.RawURLEncoding.EncodedLen(maxDNSHTTPMessage) {
			http.Error(w, "DNS query too large", http.StatusRequestEntityTooLarge)
			return
		}
		packet, err = base64.RawURLEncoding.Strict().DecodeString(values[0])
		if err != nil {
			http.Error(w, "invalid base64url DNS query", http.StatusBadRequest)
			return
		}
	default:
		w.Header().Set("Allow", "GET, POST")
		http.Error(w, "expected GET or POST", http.StatusMethodNotAllowed)
		return
	}
	answer, ok := buildDNSHTTPResponse(packet)
	if !ok {
		http.Error(w, "invalid DNS query", http.StatusBadRequest)
		return
	}
	var query dnsmessage.Message
	if err := query.Unpack(packet); err == nil && len(query.Questions) == 1 &&
		strings.HasPrefix(query.Questions[0].Name.String(), "peer-owned-") &&
		strings.HasSuffix(query.Questions[0].Name.String(), ".test.") {
		// Only task-owned queries affect this receipt; no request data is retained.
		p.dnsHTTPCount.Add(1)
	}
	w.Header().Set("Content-Type", "application/dns-message")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(answer)
}

func buildDNSHTTPResponse(packet []byte) ([]byte, bool) {
	// dnsmessage.Unpack validates records but permits trailing bytes. Check the
	// complete wire frame separately, including compressed names and EDNS data.
	if !completeDNSHTTPFrame(packet) {
		return nil, false
	}
	var query dnsmessage.Message
	if err := query.Unpack(packet); err != nil {
		return nil, false
	}
	if query.Header.Response || query.Header.OpCode != 0 || query.Header.Truncated ||
		query.Header.Authoritative || query.Header.RecursionAvailable || query.Header.RCode != dnsmessage.RCodeSuccess ||
		packet[3]&0x40 != 0 || len(query.Questions) != 1 || len(query.Answers) != 0 || len(query.Authorities) != 0 {
		return nil, false
	}
	question := query.Questions[0]
	if question.Class != dnsmessage.ClassINET || len(query.Additionals) > 1 {
		return nil, false
	}
	for _, additional := range query.Additionals {
		if additional.Header.Type != dnsmessage.TypeOPT || additional.Header.Name.String() != "." || additional.Header.TTL>>16 != 0 {
			return nil, false
		}
		options, ok := additional.Body.(*dnsmessage.UnknownResource)
		if !ok {
			return nil, false
		}
		for data := options.Data; len(data) != 0; {
			if len(data) < 4 {
				return nil, false
			}
			length := int(binary.BigEndian.Uint16(data[2:4]))
			if length > len(data)-4 {
				return nil, false
			}
			data = data[4+length:]
		}
	}
	if len(query.Additionals) == 0 && !query.Header.CheckingDisabled {
		if answer, _, ok := buildOwnedDNSResponse(packet); ok {
			return answer, true
		}
	}
	response := dnsmessage.Message{
		Header: dnsmessage.Header{
			ID:               query.Header.ID,
			Response:         true,
			Authoritative:    true,
			RecursionDesired: query.Header.RecursionDesired,
			CheckingDisabled: query.Header.CheckingDisabled,
			RCode:            dnsmessage.RCodeSuccess,
		},
		Questions: query.Questions,
	}
	for _, additional := range query.Additionals {
		// Advertise EDNS0 without reflecting arbitrary client options.
		response.Additionals = []dnsmessage.Resource{{
			Header: dnsmessage.ResourceHeader{
				Name: additional.Header.Name, Type: dnsmessage.TypeOPT,
				Class: dnsmessage.Class(4096), TTL: additional.Header.TTL & 0x8000,
			},
			Body: &dnsmessage.UnknownResource{Type: dnsmessage.TypeOPT},
		}}
	}
	// Every name is answered locally. Background lookups receive reserved
	// documentation addresses; unsupported record types receive valid NODATA.
	addressSuffix := byte(78)
	if strings.EqualFold(question.Name.String(), ownedDNSName) {
		addressSuffix = 77
	}
	header := dnsmessage.ResourceHeader{Name: question.Name, Type: question.Type, Class: question.Class, TTL: 30}
	switch question.Type {
	case dnsmessage.TypeA:
		response.Answers = []dnsmessage.Resource{{Header: header, Body: &dnsmessage.AResource{A: [4]byte{192, 0, 2, addressSuffix}}}}
	case dnsmessage.TypeAAAA:
		response.Answers = []dnsmessage.Resource{{Header: header, Body: &dnsmessage.AAAAResource{AAAA: [16]byte{0x20, 0x01, 0x0d, 0xb8, 15: addressSuffix}}}}
	}
	answer, err := response.Pack()
	return answer, err == nil && len(answer) <= maxDNSHTTPMessage
}

func completeDNSHTTPFrame(packet []byte) bool {
	if len(packet) < 12 || len(packet) > maxDNSHTTPMessage {
		return false
	}
	offset := 12
	for section := 0; section < 4; section++ {
		count := int(binary.BigEndian.Uint16(packet[4+2*section : 6+2*section]))
		for record := 0; record < count; record++ {
			var ok bool
			offset, ok = dnsHTTPNameEnd(packet, offset)
			if !ok {
				return false
			}
			if section == 0 {
				offset += 4 // QTYPE and QCLASS.
			} else {
				if offset+10 > len(packet) {
					return false
				}
				length := int(binary.BigEndian.Uint16(packet[offset+8 : offset+10]))
				offset += 10 + length // TYPE, CLASS, TTL, RDLENGTH and RDATA.
			}
			if offset > len(packet) {
				return false
			}
		}
	}
	return offset == len(packet)
}

func dnsHTTPNameEnd(packet []byte, offset int) (int, bool) {
	for offset < len(packet) {
		length := int(packet[offset])
		offset++
		switch length & 0xc0 {
		case 0:
			if length == 0 {
				return offset, true
			}
			offset += length
		case 0xc0:
			// Unpack validates the pointer target and detects pointer loops.
			return offset + 1, offset < len(packet)
		default:
			return 0, false
		}
	}
	return 0, false
}
