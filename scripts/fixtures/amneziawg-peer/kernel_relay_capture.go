package main

import "errors"

// Compare each leg in direction order. Kernel scheduling can interleave the
// directions differently; duplicate datagrams must still retain multiplicity.
type relayCaptureCheck struct {
	report     kernelRelayReport
	expected   [2][2][]relayAction // kernel/client, in/out
	seen       [2][2]int
	clientPort uint16
}

func newRelayCaptureCheck(report kernelRelayReport) (*relayCaptureCheck, error) {
	if report.Port == 0 || report.Port == 51820 || report.EndNS <= 0 || len(report.Actions) == 0 || len(report.Actions) > 4096 {
		return nil, errors.New("invalid relay report")
	}
	check := &relayCaptureCheck{report: report}
	for _, action := range report.Actions {
		direction := 0
		if action.Direction == "out" {
			direction = 1
		} else if action.Direction != "in" {
			return nil, errors.New("invalid relay direction")
		}
		if len(action.Digest) != 64 || (action.Dropped && direction == 0) {
			return nil, errors.New("invalid relay action")
		}
		check.expected[0][direction] = append(check.expected[0][direction], action)
		if !action.Dropped {
			check.expected[1][direction] = append(check.expected[1][direction], action)
		}
	}
	return check, nil
}

func (check *relayCaptureCheck) accept(packet capturedUDP, kernel bool) (bool, error) {
	leg, direction := 0, 0
	if packet.sourceIP != [4]byte{127, 0, 0, 1} || packet.destinationIP != packet.sourceIP {
		return false, errors.New("relay capture is not loopback")
	}
	if kernel {
		if packet.source == 51820 && packet.destination == check.report.Port {
			direction = 1
		} else if packet.destination != 51820 || packet.source != check.report.Port {
			return false, errors.New("kernel leg endpoint differs")
		}
	} else {
		leg = 1
		if packet.source == check.report.Port && packet.destination != check.report.Port {
			direction = 1
		} else if packet.destination != check.report.Port || packet.source == check.report.Port {
			return false, errors.New("client leg endpoint differs")
		}
		port := packet.source
		if direction == 1 {
			port = packet.destination
		}
		if port == 0 {
			return false, errors.New("zero client port")
		}
		if check.clientPort == 0 {
			check.clientPort = port
		}
		if port != check.clientPort {
			return false, errors.New("client capture endpoint changed")
		}
	}
	index := check.seen[leg][direction]
	if index >= len(check.expected[leg][direction]) {
		return false, errors.New("unexpected relay capture packet")
	}
	action := check.expected[leg][direction][index]
	if wireDigest(packet.payload) != action.Digest {
		return false, errors.New("relay capture payload differs")
	}
	check.seen[leg][direction]++
	return action.Dropped, nil
}

func (check *relayCaptureCheck) finish() error {
	for leg := range check.seen {
		for direction, count := range check.seen[leg] {
			if count != len(check.expected[leg][direction]) {
				return errors.New("relay capture is incomplete")
			}
		}
	}
	return nil
}
