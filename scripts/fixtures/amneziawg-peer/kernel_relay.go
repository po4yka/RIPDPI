package main

import (
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"time"
)

type relayAction struct {
	Direction string `json:"direction"`
	Digest    string `json:"sha256"`
	Dropped   bool   `json:"dropped"`
}
type kernelRelayReport struct {
	Port        uint16           `json:"port"`
	ForeignPort uint16           `json:"foreign_port,omitempty"`
	StartNS     int64            `json:"start_unix_ns,omitempty"`
	EndNS       int64            `json:"end_unix_ns"`
	Actions     []relayAction    `json:"actions"`
	Negative    []transportPhase `json:"negative_transport,omitempty"`
}

func wireDigest(packet []byte) string {
	sum := sha256.Sum256(packet)
	return hex.EncodeToString(sum[:])
}

// relayKernel uses one owned socket on both legs. Its caller closes the socket
// and joins this loop before reading the report. PCAP independently checks each
// action, including the absence of a client-leg copy for a dropped response.
func relayKernel(socket *net.UDPConn, kernel netip.AddrPort, observer *wireObservation, report *kernelRelayReport) error {
	var client netip.AddrPort
	buffer := make([]byte, 65535)
	for {
		n, source, err := socket.ReadFromUDPAddrPort(buffer)
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			return err
		}
		inbound := source != kernel
		target := client
		if inbound {
			if !source.Addr().IsLoopback() || !source.Addr().Is4() {
				return errors.New("foreign relay source")
			}
			if !client.IsValid() {
				client = source
			}
			if source != client {
				return errors.New("relay client endpoint changed")
			}
			target = kernel
		} else if !target.IsValid() {
			return errors.New("kernel response before client")
		}
		if len(report.Actions) == 4096 {
			return errors.New("relay action bound exceeded")
		}
		// Mutations act on the real kernel output. The report retains its original
		// digest; the capture verifier checks the transformed client-leg packets.
		if !inbound && observer.transport != nil {
			report.Actions = append(report.Actions, relayAction{"out", wireDigest(buffer[:n]), false})
			handled, err := observer.transport.send(buffer[:n], socket, target)
			if err != nil {
				return err
			}
			if handled {
				continue
			}
		} else {
			direction := "out"
			if inbound {
				direction = "in"
			}
			report.Actions = append(report.Actions, relayAction{direction, wireDigest(buffer[:n]), false})
		}
		dropped := observer.observe(buffer[:n], inbound)
		report.Actions[len(report.Actions)-1].Dropped = dropped
		if !dropped {
			written, err := socket.WriteToUDPAddrPort(buffer[:n], target)
			if err != nil {
				return err
			}
			if written != n {
				return io.ErrShortWrite
			}
		}
	}
}

func kernelControlledPeer(profile wireProfile, scenario string) error {
	socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return err
	}
	defer socket.Close()
	first, err := net.ListenPacket("udp4", "10.77.0.1:41002")
	if err != nil {
		return err
	}
	defer first.Close()
	second, err := net.ListenPacket("udp4", "10.77.0.1:41003")
	if err != nil {
		return err
	}
	defer second.Close()
	report := kernelRelayReport{Port: uint16(socket.LocalAddr().(*net.UDPAddr).Port)}
	bind := &loopbackBind{socket: socket, observation: wireObservation{profile: profile, scenario: scenario}}
	observer := &bind.observation
	negative := scenario == "negative-transport"
	if negative {
		foreign, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if err != nil {
			return err
		}
		defer foreign.Close()
		bind.transport = newTransportExercise(bind, profile)
		bind.transport.foreignSocket = foreign
		bind.transport.malformedSend = sendKernelMalformed
		observer.transport = bind.transport
		observer.evidence.start = bind.transport.start
		report.StartNS = bind.transport.start.UnixNano()
		report.ForeignPort = uint16(foreign.LocalAddr().(*net.UDPAddr).Port)
	}
	relayDone := make(chan error, 1)
	go func() {
		relayDone <- relayKernel(socket, netip.MustParseAddrPort("127.0.0.1:51820"), observer, &report)
	}()
	// Every return joins the relay. No report is read while its owner writes it.
	joined := false
	defer func() {
		socket.Close()
		if !joined {
			<-relayDone
		}
	}()
	count := 1
	if scenario == "timer-persistent" || scenario == "timer-retry-exhaust" {
		count = 0
	}
	if scenario == "timer-rekey" || scenario == "timer-reject" {
		count = 3
	}
	echoDone := make(chan error, 1)
	go func() {
		if negative {
			echoDone <- bind.transport.run(first)
			return
		}
		for range count {
			if err := echoUDPPair(first, second); err != nil {
				echoDone <- err
				return
			}
		}
		echoDone <- nil
	}()
	echoJoined := false
	defer func() {
		first.Close()
		second.Close()
		if !echoJoined {
			<-echoDone
		}
	}()
	if err = json.NewEncoder(os.Stdout).Encode(map[string]any{"endpoint": socket.LocalAddr().String(), "foreign_port": report.ForeignPort}); err != nil {
		return err
	}
	stop := make(chan struct{})
	go func() { bufio.NewScanner(os.Stdin).Scan(); close(stop) }()
	timer := time.NewTimer(30 * time.Second)
	defer timer.Stop()
	select {
	case <-stop:
	case err = <-relayDone:
		joined = true
		if err == nil {
			err = errors.New("relay stopped before runtime")
		}
		return err
	case <-timer.C:
		return errors.New("kernel timer peer deadline")
	}
	select {
	case err = <-echoDone:
		echoJoined = true
	case <-timer.C:
		return errors.New("kernel timer echo incomplete")
	}
	if err != nil {
		return err
	}
	socket.Close()
	err = <-relayDone
	joined = true
	if err != nil {
		return err
	}
	report.EndNS = time.Now().UnixNano()
	if negative {
		report.Negative = bind.transport.report()
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]any{"completed_sessions": 1, "kernel_relay": report})
}
