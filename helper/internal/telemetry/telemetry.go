// Package telemetry measures application payload, not encrypted transport overhead.
package telemetry

import (
	"context"
	"io"
	"net"
	"sync"
	"time"
)

type Stats struct {
	Mode                 string   `json:"mode"`
	Path                 string   `json:"path"`
	Relay                string   `json:"relay,omitempty"`
	BootstrapRelay       string   `json:"bootstrapRelay,omitempty"`
	ActiveConnections    int      `json:"activeConnections"`
	TxBytes              uint64   `json:"txBytes"`
	RxBytes              uint64   `json:"rxBytes"`
	TxBytesPerSecond     float64  `json:"txBytesPerSecond"`
	RxBytesPerSecond     float64  `json:"rxBytesPerSecond"`
	PeakTxBytesPerSecond float64  `json:"peakTxBytesPerSecond"`
	PeakRxBytesPerSecond float64  `json:"peakRxBytesPerSecond"`
	UptimeSeconds        float64  `json:"uptimeSeconds"`
	HelperVersion        string   `json:"helperVersion"`
	TailcatVersion       string   `json:"tailcatVersion"`
	LatencyMs            *float64 `json:"latencyMs,omitempty"`
	Peers                []Peer   `json:"peers,omitempty"`
	OmittedPeers         int      `json:"omittedPeers,omitempty"`
}

type Peer struct {
	ID    string `json:"id"`
	Path  string `json:"path"`
	Relay string `json:"relay,omitempty"`
}

type Tracker struct {
	mu                     sync.Mutex
	stats                  Stats
	started, last          time.Time
	previousTx, previousRx uint64
}

func New(mode string, now time.Time) *Tracker {
	return &Tracker{stats: Stats{Mode: mode, Path: "idle"}, started: now, last: now}
}
func (t *Tracker) AddTx(n int) { t.mu.Lock(); defer t.mu.Unlock(); t.stats.TxBytes += uint64(n) }
func (t *Tracker) AddRx(n int) { t.mu.Lock(); defer t.mu.Unlock(); t.stats.RxBytes += uint64(n) }
func (t *Tracker) Connections() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.stats.ActiveConnections
}
func (t *Tracker) Connection(delta int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.stats.ActiveConnections += delta
}
func (t *Tracker) Observe(path, relay, bootstrap, helperVersion, tailcatVersion string, latency *float64, peers []Peer) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.stats.Path = path
	t.stats.Relay = relay
	t.stats.BootstrapRelay = bootstrap
	t.stats.HelperVersion = helperVersion
	t.stats.TailcatVersion = tailcatVersion
	t.stats.LatencyMs = latency
	// Tailcat keeps historical peer keys for the life of a room. Never let
	// cumulative history overflow the bounded Java IPC parser or flood chat.
	t.stats.OmittedPeers = max(0, len(peers)-64)
	if len(peers) > 64 {
		peers = peers[:64]
	}
	t.stats.Peers = append([]Peer(nil), peers...)
}
func (t *Tracker) Sample(now time.Time) Stats {
	t.mu.Lock()
	defer t.mu.Unlock()
	elapsed := now.Sub(t.last).Seconds()
	if elapsed > 0 {
		t.stats.TxBytesPerSecond = float64(t.stats.TxBytes-t.previousTx) / elapsed
		t.stats.RxBytesPerSecond = float64(t.stats.RxBytes-t.previousRx) / elapsed
		t.stats.PeakTxBytesPerSecond = max(t.stats.PeakTxBytesPerSecond, t.stats.TxBytesPerSecond)
		t.stats.PeakRxBytesPerSecond = max(t.stats.PeakRxBytesPerSecond, t.stats.RxBytesPerSecond)
		t.previousTx = t.stats.TxBytes
		t.previousRx = t.stats.RxBytes
		t.last = now
	}
	t.stats.UptimeSeconds = max(0, now.Sub(t.started).Seconds())
	return t.stats
}

// ObservedPath only classifies a live peer. Relay is that peer's observed relay,
// never the server's selected bootstrap region.
func ObservedPath(curAddr, relay string, active bool) string {
	if !active {
		return "idle"
	}
	if curAddr != "" {
		return "udp"
	}
	if relay != "" {
		return "derp"
	}
	return "unknown"
}

type countingWriter struct {
	io.Writer
	add func(int)
}

func (w countingWriter) Write(p []byte) (int, error) {
	n, e := w.Writer.Write(p)
	w.add(n)
	return n, e
}

// Proxy closes both sockets on cancellation or when either direction ends, and
// waits for both copying goroutines before returning. local is Minecraft-side.
func Proxy(ctx context.Context, local, tunnel net.Conn, tracker *Tracker) {
	tracker.Connection(1)
	defer tracker.Connection(-1)
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(countingWriter{tunnel, tracker.AddTx}, local); done <- struct{}{} }()
	go func() { _, _ = io.Copy(countingWriter{local, tracker.AddRx}, tunnel); done <- struct{}{} }()
	completed := 0
	select {
	case <-ctx.Done():
	case <-done:
		completed = 1
	}
	local.Close()
	tunnel.Close()
	for ; completed < 2; completed++ {
		<-done
	}
}
