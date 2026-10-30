package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"time"
)

// The privileged runner owns the helper; this process only supplies the port of
// the authenticated ARM datagram. The helper fixes all other packet bytes.
func sendKernelMalformed(target net.Addr) error {
	address, ok := target.(*net.UDPAddr)
	if !ok || !address.IP.Equal(net.IPv4(10, 77, 0, 2)) {
		return errors.New("unexpected raw target")
	}
	path := os.Getenv("RIPDPI_AWG_KERNEL_RAW_SOCKET")
	if path == "" {
		return errors.New("missing owned raw helper")
	}
	connection, err := net.DialTimeout("unix", path, 2*time.Second)
	if err != nil {
		return err
	}
	defer connection.Close()
	if err = connection.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
		return err
	}
	if err = json.NewEncoder(connection).Encode(map[string]int{"port": address.Port}); err != nil {
		return err
	}
	line, err := bufio.NewReader(io.LimitReader(connection, 128)).ReadString('\n')
	if err != nil {
		return err
	}
	if line != "{\"sent\":true}\n" {
		return errors.New("raw helper did not confirm send")
	}
	return nil
}
