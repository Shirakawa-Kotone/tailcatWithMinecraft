package telemetry

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"testing"
	"time"
)

func TestSamplePayloadRatesAndPeaks(t *testing.T) {
	start := time.Unix(100, 0)
	tracker := New("join", start)
	tracker.AddTx(1000)
	tracker.AddRx(500)
	s := tracker.Sample(start.Add(2 * time.Second))
	if s.TxBytes != 1000 || s.RxBytes != 500 || s.TxBytesPerSecond != 500 || s.RxBytesPerSecond != 250 {
		t.Fatalf("wrong payload rates: %+v", s)
	}
	tracker.AddTx(100)
	s = tracker.Sample(start.Add(3 * time.Second))
	if s.TxBytesPerSecond != 100 || s.PeakTxBytesPerSecond != 500 || s.PeakRxBytesPerSecond != 250 {
		t.Fatalf("wrong sampled peaks: %+v", s)
	}
}

func TestProxyCancellationClosesBlockedStreams(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	a, local := net.Pipe()
	b, remote := net.Pipe()
	defer local.Close()
	defer remote.Close()
	tracker := New("join", time.Now())
	done := make(chan struct{})
	go func() { Proxy(ctx, local, remote, tracker); close(done) }()
	sent := make(chan struct{})
	go func() { _, _ = a.Write([]byte("hello")); close(sent) }()
	buf := make([]byte, 5)
	if _, err := io.ReadFull(b, buf); err != nil {
		t.Fatal(err)
	}
	<-sent
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("proxy did not stop")
	}
	s := tracker.Sample(time.Now())
	if s.TxBytes != 5 || s.RxBytes != 0 || s.ActiveConnections != 0 {
		t.Fatalf("wrong counters: %+v", s)
	}
	a.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := a.Read(buf); err == nil {
		t.Fatal("local stream still open")
	}
	a.Close()
	b.Close()
}

func TestObservedPathDoesNotMistakeBootstrapRelayForTraffic(t *testing.T) {
	for _, tt := range []struct {
		direct, relay string
		active        bool
		want          string
	}{
		{"", "nyc", false, "idle"}, {"", "nyc", true, "derp"}, {"127.0.0.1:123", "nyc", true, "udp"}, {"", "", true, "unknown"},
	} {
		if got := ObservedPath(tt.direct, tt.relay, tt.active); got != tt.want {
			t.Fatalf("path=%s want %s", got, tt.want)
		}
	}
}

func TestSnapshotBoundsCumulativePeersToProtocolLimit(t *testing.T) {
	tracker := New("host", time.Now())
	peers := make([]Peer, 300)
	for i := range peers {
		peers[i] = Peer{ID: "peer", Path: "udp"}
	}
	tracker.Observe("udp", "", "bootstrap", "0.2.0", "test", nil, peers)
	snapshot := tracker.Sample(time.Now())
	if len(snapshot.Peers) > 64 {
		t.Fatalf("unbounded telemetry has %d peers; Java rejects >256", len(snapshot.Peers))
	}
	encoded, err := json.Marshal(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]any
	if err = json.Unmarshal(encoded, &fields); err != nil {
		t.Fatal(err)
	}
	if fields["omittedPeers"] != float64(236) {
		t.Fatalf("omitted count=%v, want236", fields["omittedPeers"])
	}
}
