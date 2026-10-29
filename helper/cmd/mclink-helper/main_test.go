package main

import (
	"bytes"
	"context"
	"encoding/json"
	"go4.org/mem"
	"net"
	"strings"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/types/key"
	"testing"
	"time"
)

func TestRejectsNonNumericLoopbackAndInvalidPorts(t *testing.T) {
	for _, target := range []string{"localhost:25565", "192.168.1.1:25565", "127.0.0.1:0", "127.0.0.1:-1", "127.0.0.1:65536", "127.0.0.1:http", "[::1]:"} {
		if validateLoopbackTarget(target) == nil {
			t.Errorf("accepted %q", target)
		}
	}
	for _, target := range []string{"127.0.0.1:25565", "[::1]:65535"} {
		if err := validateLoopbackTarget(target); err != nil {
			t.Errorf("rejected %q: %v", target, err)
		}
	}
}
func TestCommandReaderHandlesBadInputAndCancelsOnEOF(t *testing.T) {
	var output bytes.Buffer
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	commands := make(chan string, 8)
	readCommands(ctx, strings.NewReader("garbage\n{\"type\":\"speedtest\"}\n{\"type\":\"unknown\"}\n"), newEventWriter(&output), commands, cancel)
	if ctx.Err() == nil {
		t.Fatal("EOF did not cancel session")
	}
	select {
	case command := <-commands:
		if command != "speedtest" {
			t.Fatal(command)
		}
	default:
		t.Fatal("valid command not delivered")
	}
	decoder := json.NewDecoder(&output)
	count := 0
	for decoder.More() {
		var e event
		if err := decoder.Decode(&e); err != nil {
			t.Fatal(err)
		}
		if e.Code != "bad_command" {
			t.Fatalf("unexpected event: %+v", e)
		}
		count++
	}
	if count != 2 {
		t.Fatalf("expected two command errors, got %d", count)
	}
}

func TestPoolCapsPendingStreamsAndClosesAllOnShutdown(t *testing.T) {
	var pool streamPool
	var streams []*stream
	var peers []net.Conn
	for i := 0; i < 64; i++ {
		a, b := net.Pipe()
		peers = append(peers, b)
		s := pool.begin(a)
		if s == nil {
			t.Fatal("stream rejected before limit")
		}
		streams = append(streams, s)
	}
	a, b := net.Pipe()
	if pool.begin(a) != nil {
		t.Fatal("accepted 65th pending stream")
	}
	b.Close()
	pool.close()
	for i, p := range peers {
		p.SetReadDeadline(time.Now().Add(time.Second))
		if _, e := p.Read(make([]byte, 1)); e == nil {
			t.Fatal("shutdown did not close stream")
		}
		p.Close()
		streams[i].done()
	}
	pool.wg.Wait()
	a, b = net.Pipe()
	defer b.Close()
	if pool.begin(a) != nil {
		t.Fatal("accepted stream after shutdown")
	}
}
func TestHostObservationsAndPerPeerSpeedtestGate(t *testing.T) {
	directKey := key.NodePublicFromRaw32(mem.B(bytes.Repeat([]byte{1}, 32)))
	relayKey := key.NodePublicFromRaw32(mem.B(bytes.Repeat([]byte{2}, 32)))
	st := &ipnstate.Status{Peer: map[key.NodePublic]*ipnstate.PeerStatus{
		directKey: {Active: true, CurAddr: "192.0.2.1:1234", Relay: "nyc"},
		relayKey:  {Active: true, Relay: "nyc"},
	}}
	path, relay, peers := hostObservations(st, true)
	if path != "mixed" || relay != "nyc" || len(peers) != 2 {
		t.Fatalf("wrong host observations: %s %s %+v", path, relay, peers)
	}
	if serverPeerDirect(st, &net.TCPAddr{IP: net.ParseIP("fd7a:115c:a1e0:202:202:202:202:202"), Port: 333}) {
		t.Fatal("direct peer authorized a different relayed peer")
	}
	if !serverPeerDirect(st, &net.TCPAddr{IP: net.ParseIP("fd7a:115c:a1e0:101:101:101:101:101"), Port: 333}) {
		t.Fatal("direct peer not recognized")
	}
	st.Peer[directKey].Active = false
	if serverPeerDirect(st, &net.TCPAddr{IP: net.ParseIP("fd7a:115c:a1e0:101:101:101:101:101"), Port: 333}) {
		t.Fatal("stale endpoint authorized benchmark")
	}
	if path, _, _ := hostObservations(st, false); path != "idle" {
		t.Fatalf("no gameplay connections path=%s", path)
	}
}

func TestHostObservationsExcludeInactivePeerHistory(t *testing.T) {
	st := &ipnstate.Status{Peer: map[key.NodePublic]*ipnstate.PeerStatus{}}
	for i := 0; i < 300; i++ {
		st.Peer[key.NewNode().Public()] = &ipnstate.PeerStatus{Active: false, Relay: "old"}
	}
	st.Peer[key.NewNode().Public()] = &ipnstate.PeerStatus{Active: true, CurAddr: "192.0.2.1:1234"}
	path, _, peers := hostObservations(st, true)
	if path != "udp" || len(peers) != 1 {
		t.Fatalf("historical peers leaked: path=%s count=%d", path, len(peers))
	}
}
