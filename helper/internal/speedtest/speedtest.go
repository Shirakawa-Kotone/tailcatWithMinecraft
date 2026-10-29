// Package speedtest implements receiver-measured, bounded direct-path probes.
// Each direction uses its own connection, so a phase timeout can return a
// useful partial measurement without leaving an unread payload in the protocol.
package speedtest

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"time"
)

const (
	maxBytes      = 8 * 1024 * 1024
	phaseDuration = 2 * time.Second
	maxDuration   = 6 * time.Second
)

var ErrPath = errors.New("speed test requires an observed direct UDP path; shared/public DERP tests are disabled")

type Result struct {
	State                  string   `json:"state"`
	UploadBytesPerSecond   float64  `json:"uploadBytesPerSecond,omitempty"`
	DownloadBytesPerSecond float64  `json:"downloadBytesPerSecond,omitempty"`
	LatencyMs              *float64 `json:"latencyMs,omitempty"`
	Message                string   `json:"message,omitempty"`
}
type zeroReader struct{}

func (zeroReader) Read(p []byte) (int, error) { clear(p); return len(p), nil }
func measuredEnd(err error) bool {
	if err == nil || errors.Is(err, io.EOF) {
		return true
	}
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}
func watch(ctx context.Context, c net.Conn, direct func() bool) func() {
	child, cancel := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() {
		defer close(done)
		ticker := time.NewTicker(100 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-child.Done():
				if ctx.Err() != nil {
					c.Close()
				}
				return
			case <-ticker.C:
				if !direct() {
					c.Close()
					return
				}
			}
		}
	}()
	return func() { cancel(); <-done; c.Close() }
}

// Run measures upload at the peer receiver, download at this receiver. A single
// handshake ACK per direction measures protocol RTT; there are no per-chunk ACKs
// constraining throughput to one chunk per RTT.
func Run(parent context.Context, dial func(context.Context) (net.Conn, error), direct func() bool) (Result, error) {
	ctx, cancel := context.WithTimeout(parent, maxDuration)
	defer cancel()
	var r Result
	for phase := byte(1); phase <= 2; phase++ {
		if ctx.Err() != nil {
			return r, ctx.Err()
		}
		if !direct() {
			return r, ErrPath
		}
		c, e := dial(ctx)
		if e != nil {
			return r, e
		}
		stop := watch(ctx, c, direct)
		rate, latency, e := runPhase(ctx, c, phase)
		stop()
		if e != nil {
			return r, e
		}
		if phase == 1 {
			r.UploadBytesPerSecond = rate
			r.LatencyMs = &latency
		} else {
			r.DownloadBytesPerSecond = rate
		}
	}
	r.State = "complete"
	r.Message = "Receiver-measured session throughput; each direction is limited to 8 MiB or 2 seconds."
	return r, nil
}
func runPhase(ctx context.Context, c net.Conn, phase byte) (rate, latency float64, err error) {
	c.SetDeadline(time.Now().Add(time.Second))
	start := time.Now()
	if _, err = c.Write(append([]byte("MCLS1"), phase)); err != nil {
		return
	}
	ack := make([]byte, 1)
	if _, err = io.ReadFull(c, ack); err != nil {
		return
	}
	if ack[0] != 1 {
		err = ErrPath
		return
	}
	latency = float64(time.Since(start)) / float64(time.Millisecond)
	c.SetDeadline(time.Now().Add(phaseDuration))
	if phase == 1 {
		_, writeErr := io.CopyN(c, zeroReader{}, maxBytes)
		if !measuredEnd(writeErr) {
			err = writeErr
			return
		}
		// Only the peer's receive count is an upload measurement. Bytes accepted
		// into a local socket buffer are not necessarily delivered to the peer.
		c.SetDeadline(time.Now().Add(time.Second))
		var summary [16]byte
		if _, err = io.ReadFull(c, summary[:]); err != nil {
			return
		}
		n := binary.BigEndian.Uint64(summary[:8])
		duration := binary.BigEndian.Uint64(summary[8:])
		if n == 0 || n > maxBytes || duration == 0 || duration > uint64(maxDuration) {
			err = errors.New("invalid measurement summary")
			return
		}
		rate = float64(n) / (float64(duration) / float64(time.Second))
	} else {
		start = time.Now()
		var n int64
		n, err = io.CopyN(io.Discard, c, maxBytes)
		if !measuredEnd(err) || n == 0 {
			return
		}
		rate = float64(n) / time.Since(start).Seconds()
		err = nil
	}
	if ctx.Err() != nil {
		err = ctx.Err()
	}
	return
}

// Serve enforces receiver/sender byte and time limits independently of the
// initiator and checks this accepted peer's actual observed path throughout.
func Serve(parent context.Context, c net.Conn, direct func() bool) error {
	ctx, cancel := context.WithTimeout(parent, maxDuration)
	defer cancel()
	defer watch(ctx, c, direct)()
	c.SetDeadline(time.Now().Add(time.Second))
	var hello [6]byte
	if _, e := io.ReadFull(c, hello[:]); e != nil {
		return e
	}
	if string(hello[:5]) != "MCLS1" || (hello[5] != 1 && hello[5] != 2) {
		return errors.New("invalid speed test protocol")
	}
	if !direct() {
		_, _ = c.Write([]byte{0})
		return ErrPath
	}
	if _, e := c.Write([]byte{1}); e != nil {
		return e
	}
	c.SetDeadline(time.Now().Add(phaseDuration))
	if hello[5] == 1 {
		start := time.Now()
		n, e := io.CopyN(io.Discard, c, maxBytes)
		duration := time.Since(start)
		if !measuredEnd(e) {
			return e
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		var summary [16]byte
		binary.BigEndian.PutUint64(summary[:8], uint64(n))
		binary.BigEndian.PutUint64(summary[8:], uint64(max(duration, time.Nanosecond)))
		c.SetDeadline(time.Now().Add(time.Second))
		_, e = c.Write(summary[:])
		return e
	}
	_, e := io.CopyN(c, zeroReader{}, maxBytes)
	if !measuredEnd(e) {
		return e
	}
	return ctx.Err()
}
