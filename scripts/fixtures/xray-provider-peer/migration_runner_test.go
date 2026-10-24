package main

import (
	"context"
	"os/exec"
	"path/filepath"
	"syscall"
	"testing"
	"time"
)

func TestMigrationRunnerReadsThreeRealPeerManifests(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 90*time.Second)
	defer cancel()
	directory := t.TempDir()
	binary := filepath.Join(directory, "xray-peer")
	build := exec.CommandContext(ctx, "go", "build", "-mod=readonly", "-p", "2", "-o", binary, ".")
	if output, err := build.CombinedOutput(); err != nil {
		t.Fatalf("build real migration peers: %v\n%s", err, output)
	}
	check := exec.CommandContext(ctx, "python3", "-c", `
import importlib.util
import json
import pathlib
import sys
spec = importlib.util.spec_from_file_location("android_runner", "../../ci/run-android-local-acceptance.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
owned = []
try:
    peers = [runner.start_migration_peer(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), "migration-regression", name, owned) for name in ("old", "reserve", "replacement")]
    assert len({peer["uuid"] for peer in peers}) == 3
    assert len({peer["tcpPort"] for peer in peers}) == 3
    for peer in peers:
        assert peer["runId"] == "migration-regression"
        assert runner.fetch("127.0.0.1", peer["controlPort"], "manifest")["uuid"] == peer["uuid"]
    initial, updated = runner.migration_payloads(*peers, "10.0.2.2", 9443, "owned-token", {"migration_id":"migration-regression", "delivery_id":"delivery-regression", "receipt_key":"ab"*32})
    initial, updated = json.loads(initial), json.loads(updated)
    assert [outbound["tag"] for outbound in initial["outbounds"]] == ["select", "old", "reserve"]
    assert [outbound["tag"] for outbound in updated["outbounds"]] == ["select", "reserve", "replacement"]
    assert initial["outbounds"][1]["uuid"] == peers[0]["uuid"]
    assert updated["outbounds"][2]["uuid"] == peers[2]["uuid"]
    assert updated["outbounds"][2]["server_port"] == peers[2]["tcpPort"]
    assert updated["outbounds"][2]["tls"]["reality"]["public_key"] == peers[2]["publicKey"]
finally:
    errors = []
    for peer in reversed(owned):
        try:
            peer.close()
        except Exception as error:
            errors.append(error)
    if errors:
        raise RuntimeError("migration peer cleanup failed") from errors[0]
`, binary, directory)
	check.Cancel = func() error { return check.Process.Signal(syscall.SIGINT) }
	check.WaitDelay = 35 * time.Second
	if output, err := check.CombinedOutput(); err != nil {
		t.Fatalf("real Python runner and delivery factory: %v\n%s", err, output)
	}
}
