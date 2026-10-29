package main

import "testing"

func TestPeerCountersExcludeCredentialsAndRequireOnePeer(t *testing.T) {
	valid := "private_key=fixture-private\npreshared_key=fixture-psk\nrx_bytes=1528\ntx_bytes=1368\nlast_handshake_time_sec=1\nlast_handshake_time_nsec=2\n"
	counters, err := parsePeerCounters(valid)
	if err != nil {
		t.Fatal(err)
	}
	if len(counters) != 4 || counters["rx_bytes"] != 1528 {
		t.Fatalf("unexpected counters: %v", counters)
	}
	for _, invalid := range []string{"rx_bytes=1\n", valid + "rx_bytes=10\n", valid + "tx_bytes=-1\n"} {
		if _, err := parsePeerCounters(invalid); err == nil {
			t.Fatal("invalid counter record accepted")
		}
	}
}

func TestEventLimitRetainsAllLengthCounts(t *testing.T) {
	var evidence wireEvidence
	packet := wirePacket(156, 8, 100)
	for range 1100 {
		evidence.record(legacyWireProfile, packet, true, 0)
	}
	if len(evidence.Events) != 1024 || evidence.EventsDropped != 76 || evidence.Lengths[0][0][156] != 1100 {
		t.Fatal("bounded journal lost the complete histogram")
	}
	for index, event := range evidence.Events {
		if event.ID != index+1 || event.Direction != "in" || event.Type != 1 || event.Length != 156 {
			t.Fatal("journal identity differs from observed packets")
		}
		if index > 0 && event.AtNS < evidence.Events[index-1].AtNS {
			t.Fatal("event clock moved backwards")
		}
	}
}
