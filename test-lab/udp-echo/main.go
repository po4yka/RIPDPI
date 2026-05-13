package main

import (
	"errors"
	"log"
	"net"
	"os"
)

const maxDatagramSize = 64 * 1024

func main() {
	addr := getenv("UDP_ECHO_ADDR", ":9001")
	conn, err := net.ListenPacket("udp", addr)
	if err != nil {
		log.Fatalf("listen %s: %v", addr, err)
	}
	defer conn.Close()

	log.Printf("udp echo listening on %s", conn.LocalAddr())
	if err := serve(conn); err != nil {
		log.Fatalf("serve: %v", err)
	}
}

func serve(conn net.PacketConn) error {
	buf := make([]byte, maxDatagramSize)
	for {
		n, src, err := conn.ReadFrom(buf)
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			return err
		}

		log.Printf("echo source=%s size=%d", src.String(), n)
		if _, err := conn.WriteTo(buf[:n], src); err != nil {
			return err
		}
	}
}

func getenv(key, fallback string) string {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	return value
}
