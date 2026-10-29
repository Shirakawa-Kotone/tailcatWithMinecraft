package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/tailscale/mclink/helper/internal/invite"
	"github.com/tailscale/mclink/helper/internal/speedtest"
	"github.com/tailscale/mclink/helper/internal/telemetry"
	"github.com/tailscale/tailcat"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/types/key"
	"tailscale.com/wgengine/filter"
)

var helperVersion = "0.2.0"

const tailcatVersion = "fe50bc39d802+mclink-backports"
const measurementPort = 25566

func readCommands(ctx context.Context, in io.Reader, out *eventWriter, commands chan<- string, cancel context.CancelFunc) {
	defer cancel()
	scanner := bufio.NewScanner(in)
	scanner.Buffer(make([]byte, 1024), 4096)
	for scanner.Scan() {
		var cmd struct {
			Type string `json:"type"`
		}
		err := json.Unmarshal(scanner.Bytes(), &cmd)
		if err != nil || (cmd.Type != "speedtest" && cmd.Type != "cancel_speedtest") {
			_ = out.send(event{Type: "error", Code: "bad_command", Message: "expected speedtest or cancel_speedtest NDJSON command"})
			continue
		}
		select {
		case commands <- cmd.Type:
		case <-ctx.Done():
			return
		default:
			_ = out.send(event{Type: "error", Code: "bad_command", Message: "command queue is full"})
		}
	}
	if scanner.Err() != nil {
		_ = out.send(event{Type: "error", Code: "bad_command", Message: "command exceeds 4096 bytes or stdin failed"})
	}
}

// streamPool includes pending dials and closes sockets before waiting, so blocked
// Minecraft sockets cannot keep session shutdown alive indefinitely.
type streamPool struct {
	mu      sync.Mutex
	closed  bool
	streams map[*stream]bool
	wg      sync.WaitGroup
}
type stream struct {
	pool  *streamPool
	conns []net.Conn
}

func (p *streamPool) begin(c net.Conn) *stream {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed || len(p.streams) >= maxConnections {
		c.Close()
		return nil
	}
	if p.streams == nil {
		p.streams = map[*stream]bool{}
	}
	s := &stream{pool: p, conns: []net.Conn{c}}
	p.streams[s] = true
	p.wg.Add(1)
	return s
}
func (s *stream) add(c net.Conn) bool {
	s.pool.mu.Lock()
	defer s.pool.mu.Unlock()
	if s.pool.closed {
		c.Close()
		return false
	}
	s.conns = append(s.conns, c)
	return true
}
func (s *stream) done() {
	p := s.pool
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, c := range s.conns {
		c.Close()
	}
	delete(p.streams, s)
	p.wg.Done()
}
func (p *streamPool) close() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.closed = true
	for s := range p.streams {
		for _, c := range s.conns {
			c.Close()
		}
	}
}

func runHost(parent context.Context, out *eventWriter, args []string, commands <-chan string) (string, error) {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	fs := flag.NewFlagSet("host", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	target := fs.String("target", "", "numeric loopback Minecraft address")
	if e := fs.Parse(args); e != nil {
		return "usage", e
	}
	if fs.NArg() != 0 {
		return "usage", errors.New("unexpected host arguments")
	}
	if e := validateLoopbackTarget(*target); e != nil {
		return "usage", e
	}
	priv := key.NewNode()
	ci := tailcat.ConnInfo{RegionID: -1}
	if e := ci.Expand(ctx, tailcat.ExpandForServer); e != nil {
		return "derp_unreachable", errors.New("could not select bootstrap relay")
	}
	if len(ci.Region) != 1 {
		return "derp_unreachable", errors.New("no bootstrap relay available")
	}
	region := ci.Region[0]
	// Tailcat diagnostics can contain node/connection blobs; never route them to
	// the game's log. Public status APIs provide all user-facing observations.
	server, e := tailcat.NewServer(priv, func(string, ...any) {}, region)
	if e != nil {
		return "helper_failed", errors.New("could not create Tailcat server")
	}
	var pool streamPool
	probeGate := make(chan struct{}, 1)
	tracker := telemetry.New("host", time.Now())
	var sessionWG sync.WaitGroup
	defer func() { cancel(); pool.close(); server.Close(); pool.wg.Wait(); sessionWG.Wait() }()
	server.ServedTCPPorts = []filter.PortRange{{First: virtualPort, Last: measurementPort}}
	server.OnTCP = func(port uint16) func(net.Conn) {
		if port != virtualPort && port != measurementPort {
			return nil
		}
		return func(tunnel net.Conn) {
			s := pool.begin(tunnel)
			if s == nil {
				return
			}
			defer s.done()
			if port == measurementPort {
				select {
				case probeGate <- struct{}{}:
					defer func() { <-probeGate }()
				case <-ctx.Done():
					return
				case <-time.After(100 * time.Millisecond):
					return
				}
				_ = speedtest.Serve(ctx, tunnel, func() bool { return serverPeerDirect(server.Status(), tunnel.RemoteAddr()) })
				return
			}
			dialCtx, stop := context.WithTimeout(ctx, 5*time.Second)
			defer stop()
			backend, e := (&net.Dialer{}).DialContext(dialCtx, "tcp", *target)
			if e != nil || !s.add(backend) {
				return
			}
			// Host network upload is backend->tunnel; download is tunnel->backend.
			telemetry.Proxy(ctx, backend, tunnel, tracker)
		}
	}
	if e := server.Start(); e != nil {
		return "derp_unreachable", errors.New("could not start Tailcat server")
	}
	publicCI := tailcat.ConnInfo{ServerPublic: tailcat.NodePublic{NodePublic: priv.Public()}, RegionID: region.RegionID}
	encoded, e := invite.New(string(publicCI.ConnBlob())).Encode()
	if e != nil {
		return "helper_failed", e
	}
	if e = out.send(event{Type: "ready", ProtocolVersion: 1, Mode: "host", Invite: encoded}); e != nil {
		return "helper_failed", e
	}
	bootstrap := fmt.Sprintf("%s (%s)", region.RegionCode, region.RegionName)
	sessionWG.Add(1)
	go func() {
		defer sessionWG.Done()
		tick := time.NewTicker(time.Second)
		defer tick.Stop()
		for {
			path, relay, peers := hostObservations(server.Status(), tracker.Connections() > 0)
			tracker.Observe(path, relay, bootstrap, helperVersion, tailcatVersion, nil, peers)
			s := tracker.Sample(time.Now())
			if out.send(event{Type: "stats", ProtocolVersion: 1, Stats: &s}) != nil {
				cancel()
				return
			}
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
			}
		}
	}()
	for {
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case cmd := <-commands:
			if cmd == "speedtest" {
				sendSpeed(out, speedtest.Result{State: "error", Message: "Run /tc-speedtest on the joining client to measure its connection to this host."})
			}
		}
	}
}

func runJoin(parent context.Context, out *eventWriter, args []string, commands <-chan string) (string, error) {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	fs := flag.NewFlagSet("join", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	encoded := fs.String("invite", "", "mcl1 invitation")
	if e := fs.Parse(args); e != nil {
		return "usage", e
	}
	if fs.NArg() != 0 {
		return "usage", errors.New("unexpected join arguments")
	}
	inv, e := invite.Decode(*encoded)
	if e != nil {
		return "invalid_invite", e
	}
	client, e := tailcat.NewClient(func(string, ...any) {}, tailcat.ConnBlob(inv.Tailcat), key.NewNode())
	if e != nil {
		return "invalid_invite", errors.New("could not create Tailcat client")
	}
	listener, e := net.Listen("tcp", "127.0.0.1:0")
	if e != nil {
		client.Close()
		return "helper_failed", e
	}
	bootstrap := ""
	if ci, e := tailcat.ParseConnBlob(tailcat.ConnBlob(inv.Tailcat)); e == nil {
		if ci.RegionID != 0 {
			bootstrap = fmt.Sprintf("region %d", ci.RegionID)
		} else if len(ci.Region) > 0 {
			bootstrap = ci.Region[0].RegionName
			if bootstrap == "" {
				bootstrap = ci.Region[0].RegionCode
			}
		}
	}
	tracker := telemetry.New("join", time.Now())
	var pool streamPool
	var sessionWG sync.WaitGroup
	var pathMu sync.Mutex
	path, relay := "unknown", ""
	var latency *float64
	lastObservation := time.Time{}
	observe := func(observeCtx context.Context) {
		pingCtx, stop := context.WithTimeout(observeCtx, 750*time.Millisecond)
		defer stop()
		r, e := client.DiscoPing(pingCtx)
		pathMu.Lock()
		defer pathMu.Unlock()
		lastObservation = time.Now()
		path = "unknown"
		relay = ""
		latency = nil
		if e != nil || r == nil {
			return
		}
		ms := r.LatencySeconds * 1000
		latency = &ms
		if r.Endpoint != "" {
			path = "udp"
		} else if r.DERPRegionID != 0 {
			path = "derp"
			relay = r.DERPRegionCode
			if relay == "" {
				relay = fmt.Sprint(r.DERPRegionID)
			}
		}
	}
	direct := func() bool {
		pathMu.Lock()
		defer pathMu.Unlock()
		return path == "udp" && time.Since(lastObservation) < 1500*time.Millisecond
	}
	var probeBusy atomic.Bool
	var probeMu sync.Mutex
	var probeCancel context.CancelFunc
	defer func() {
		cancel()
		listener.Close()
		pool.close()
		client.Close()
		pool.wg.Wait()
		sessionWG.Wait()
	}()
	context.AfterFunc(ctx, func() { listener.Close(); pool.close() })
	if e = out.send(event{Type: "ready", ProtocolVersion: 1, Mode: "join", Address: listener.Addr().String()}); e != nil {
		return "helper_failed", e
	}
	sessionWG.Add(1)
	go func() {
		defer sessionWG.Done()
		tick := time.NewTicker(time.Second)
		defer tick.Stop()
		for {
			observe(ctx)
			pathMu.Lock()
			p, r, l := path, relay, latency
			pathMu.Unlock()
			if tracker.Connections() == 0 {
				p = "idle"
				r = ""
			}
			tracker.Observe(p, r, bootstrap, helperVersion, tailcatVersion, l, nil)
			s := tracker.Sample(time.Now())
			if out.send(event{Type: "stats", ProtocolVersion: 1, Stats: &s}) != nil {
				cancel()
				return
			}
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
			}
		}
	}()
	sessionWG.Add(1)
	go func() {
		defer sessionWG.Done()
		for {
			select {
			case <-ctx.Done():
				return
			case cmd := <-commands:
				if cmd == "cancel_speedtest" {
					probeMu.Lock()
					if probeCancel != nil {
						probeCancel()
					}
					probeMu.Unlock()
					continue
				}
				if !probeBusy.CompareAndSwap(false, true) {
					sendSpeed(out, speedtest.Result{State: "error", Message: "A speed test is already running."})
					continue
				}
				testCtx, stop := context.WithTimeout(ctx, 8*time.Second)
				probeMu.Lock()
				probeCancel = stop
				probeMu.Unlock()
				sessionWG.Add(1)
				go func() {
					defer sessionWG.Done()
					defer probeBusy.Store(false)
					defer stop()
					sendSpeed(out, speedtest.Result{State: "running", Message: "Checking direct path; shared/public DERP tests are disabled."})
					observe(testCtx)
					if !direct() {
						if ctx.Err() == nil {
							sendSpeed(out, speedtest.Result{State: "error", Message: speedtest.ErrPath.Error()})
						}
						return
					}
					monitorDone := make(chan struct{})
					go func() {
						defer close(monitorDone)
						ticker := time.NewTicker(100 * time.Millisecond)
						defer ticker.Stop()
						for {
							select {
							case <-testCtx.Done():
								return
							case <-ticker.C:
								observe(testCtx)
								if !direct() {
									stop()
									return
								}
							}
						}
					}()
					result, e := speedtest.Run(testCtx, func(dialCtx context.Context) (net.Conn, error) { return client.DialTCPPort(dialCtx, measurementPort) }, direct)
					stop()
					<-monitorDone
					if ctx.Err() != nil {
						return
					}
					if e != nil {
						result = speedtest.Result{State: "error", Message: "Measurement stopped: path changed, test was canceled, or peer service failed."}
					}
					sendSpeed(out, result)
				}()
			}
		}
	}()
	for {
		local, e := listener.Accept()
		if e != nil {
			if ctx.Err() != nil {
				return "", ctx.Err()
			}
			return "helper_failed", e
		}
		s := pool.begin(local)
		if s == nil {
			continue
		}
		go func() {
			defer s.done()
			dialCtx, stop := context.WithTimeout(ctx, 15*time.Second)
			defer stop()
			tunnel, e := client.DialTCPPort(dialCtx, virtualPort)
			if e != nil || !s.add(tunnel) {
				return
			}
			telemetry.Proxy(ctx, local, tunnel, tracker)
		}()
	}
}

func sendSpeed(out *eventWriter, r speedtest.Result) {
	_ = out.send(event{Type: "speedtest", ProtocolVersion: 1, Speedtest: &r})
}

func hostObservations(status *ipnstate.Status, connected bool) (string, string, []telemetry.Peer) {
	if status == nil {
		return "unknown", "", nil
	}
	paths := map[string]bool{}
	relay := ""
	peers := []telemetry.Peer{}
	for k, p := range status.Peer {
		if !p.Active {
			continue
		}
		path := telemetry.ObservedPath(p.CurAddr, p.Relay, p.Active)
		if p.Active && p.PeerRelay != "" {
			path = "unknown"
		}
		if path != "idle" {
			paths[path] = true
		}
		r := ""
		if path == "derp" {
			r = p.Relay
			if relay == "" {
				relay = r
			} else if relay != r {
				relay = "multiple"
			}
		}
		peers = append(peers, telemetry.Peer{ID: k.ShortString(), Path: path, Relay: r})
	}
	sort.Slice(peers, func(i, j int) bool { return peers[i].ID < peers[j].ID })
	if !connected {
		return "idle", "", peers
	}
	if paths["unknown"] || len(paths) == 0 {
		return "unknown", relay, peers
	}
	if paths["udp"] && paths["derp"] {
		return "mixed", relay, peers
	}
	if paths["udp"] {
		return "udp", "", peers
	}
	return "derp", relay, peers
}

// Tailcat's public virtual IPv6 address is fd7a:115c:a1e0::/48 followed by
// 80 public-key bits. Matching the accepted socket prevents one direct peer
// from authorizing a test by a different relayed peer.
func serverPeerDirect(status *ipnstate.Status, remote net.Addr) bool {
	if status == nil || remote == nil {
		return false
	}
	ap, e := netip.ParseAddrPort(remote.String())
	if e != nil {
		return false
	}
	for k, p := range status.Peer {
		raw := k.Raw32()
		var b [16]byte
		copy(b[:6], []byte{0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0})
		copy(b[6:], raw[:10])
		if ap.Addr() == netip.AddrFrom16(b) {
			return p.Active && p.CurAddr != "" && p.PeerRelay == ""
		}
	}
	return false
}
