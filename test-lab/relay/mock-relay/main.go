package main

import (
	"bufio"
	"encoding/json"
	"log"
	"net"
	"os"
	"strings"
	"time"
)

type request struct {
	Auth string `json:"auth"`
}

type response struct {
	OK      bool   `json:"ok"`
	Code    string `json:"code"`
	Message string `json:"message"`
}

func main() {
	addr := getenv("MOCK_RELAY_ADDR", ":10080")
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatalf("listen %s: %v", addr, err)
	}
	defer listener.Close()

	log.Printf("mock relay listening on %s", listener.Addr())
	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Fatalf("accept: %v", err)
		}
		go handle(conn, getenv("MOCK_RELAY_MODE", "ok"))
	}
}

func handle(conn net.Conn, mode string) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))

	if mode == "malformed" {
		_, _ = conn.Write([]byte("not-json\n"))
		return
	}

	line, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil {
		writeResponse(conn, response{OK: false, Code: "READ_FAILED", Message: err.Error()})
		return
	}

	var req request
	if err := json.Unmarshal([]byte(strings.TrimSpace(line)), &req); err != nil {
		writeResponse(conn, response{OK: false, Code: "BAD_HANDSHAKE", Message: "handshake must be JSON"})
		return
	}

	if mode == "auth_fail" || req.Auth == "fail" {
		writeResponse(conn, response{OK: false, Code: "AUTH_FAILED", Message: "mock relay rejected credentials"})
		return
	}

	writeResponse(conn, response{OK: true, Code: "READY", Message: "mock relay ready"})
}

func writeResponse(conn net.Conn, resp response) {
	payload, err := json.Marshal(resp)
	if err != nil {
		return
	}
	_, _ = conn.Write(append(payload, '\n'))
}

func getenv(key, fallback string) string {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	return value
}
