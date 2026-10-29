package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"

	"github.com/tailscale/mclink/helper/internal/speedtest"
	"github.com/tailscale/mclink/helper/internal/telemetry"
)

const (
	virtualPort    = 25565
	maxConnections = 64
)

type event struct {
	Type            string            `json:"type"`
	ProtocolVersion int               `json:"protocolVersion,omitempty"`
	Stats           *telemetry.Stats  `json:"stats,omitempty"`
	Speedtest       *speedtest.Result `json:"speedtest,omitempty"`
	HelperVersion   string            `json:"helperVersion,omitempty"`
	TailcatVersion  string            `json:"tailcatVersion,omitempty"`
	Mode            string            `json:"mode,omitempty"`
	Invite          string            `json:"invite,omitempty"`
	Address         string            `json:"address,omitempty"`
	Code            string            `json:"code,omitempty"`
	Message         string            `json:"message,omitempty"`
}

type eventWriter struct {
	mu  sync.Mutex
	enc *json.Encoder
}

func newEventWriter(w io.Writer) *eventWriter { return &eventWriter{enc: json.NewEncoder(w)} }
func (w *eventWriter) send(e event) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.enc.Encode(e)
}

func main() {
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)
	out := newEventWriter(os.Stdout)
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if len(os.Args) == 2 && os.Args[1] == "version" {
		_ = out.send(event{Type: "version", ProtocolVersion: 1, HelperVersion: helperVersion, TailcatVersion: tailcatVersion})
		return
	}
	commands := make(chan string, 8)
	go readCommands(ctx, os.Stdin, out, commands, cancel)

	if len(os.Args) < 2 {
		_ = out.send(event{Type: "error", Code: "usage", Message: "expected host or join command"})
		return
	}
	var code string
	var err error
	switch os.Args[1] {
	case "host":
		code, err = runHost(ctx, out, os.Args[2:], commands)
	case "join":
		code, err = runJoin(ctx, out, os.Args[2:], commands)
	default:
		code, err = "usage", fmt.Errorf("unknown command %q", os.Args[1])
	}
	if err != nil && !errors.Is(err, context.Canceled) {
		_ = out.send(event{Type: "error", Code: code, Message: err.Error()})
	}
	_ = out.send(event{Type: "stopped"})
}

func validateLoopbackTarget(target string) error {
	host, port, err := net.SplitHostPort(target)
	if err != nil {
		return fmt.Errorf("invalid --target: %w", err)
	}
	for _, c := range port {
		if c < '0' || c > '9' {
			return errors.New("--target requires a numeric port")
		}
	}
	n, err := strconv.Atoi(port)
	if err != nil || n < 1 || n > 65535 || strings.TrimSpace(port) != port {
		return errors.New("--target requires a numeric port between 1 and 65535")
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return errors.New("--target must be a numeric loopback address")
	}
	return nil
}
