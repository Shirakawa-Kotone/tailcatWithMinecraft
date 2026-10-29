package speedtest

import (
	"context"
	"net"
	"testing"
	"time"
)

func TestRefusesRelayBeforeWritingPayload(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	got := make(chan error, 1)
	go func() {
		_, e := Run(context.Background(), func(context.Context) (net.Conn, error) { return a, nil }, func() bool { return false })
		got <- e
	}()
	if err := <-got; err == nil {
		t.Fatal("public relay test accepted")
	}
	b.SetReadDeadline(time.Now().Add(time.Second))
	buf := make([]byte, 1)
	if n, _ := b.Read(buf); n != 0 {
		t.Fatal("wrote test traffic before direct check")
	}
}
func TestCancellationStopsBlockedSpeedtest(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		_, e := Run(ctx, func(context.Context) (net.Conn, error) { return a, nil }, func() bool { return true })
		done <- e
	}()
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancel ignored")
		}
	case <-time.After(time.Second):
		t.Fatal("test blocked after cancellation")
	}
}
func TestBoundedDirectTestAndServerCleanup(t *testing.T) {
	done := make(chan error, 2)
	dial := func(context.Context) (net.Conn, error) {
		a, b := net.Pipe()
		go func() { done <- Serve(context.Background(), b, func() bool { return true }) }()
		return a, nil
	}
	result, err := Run(context.Background(), dial, func() bool { return true })
	if err != nil {
		t.Fatal(err)
	}
	if result.UploadBytesPerSecond <= 0 || result.DownloadBytesPerSecond <= 0 || result.LatencyMs == nil {
		t.Fatalf("no measurements: %+v", result)
	}
	for i := 0; i < 2; i++ {
		select {
		case err := <-done:
			if err != nil {
				t.Fatal(err)
			}
		case <-time.After(time.Second):
			t.Fatal("server did not clean up")
		}
	}
}
func TestPathChangeStopsBeforeFurtherPayload(t *testing.T) {
	a, b := net.Pipe()
	done := make(chan error, 1)
	go func() { done <- Serve(context.Background(), b, func() bool { return false }) }()
	_, err := Run(context.Background(), func(context.Context) (net.Conn, error) { return a, nil }, func() bool { return true })
	if err == nil {
		t.Fatal("server accepted relayed path")
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("server blocked")
	}
}

type controlDelayConn struct{ net.Conn }

func (c controlDelayConn) Write(p []byte) (int, error) {
	if len(p) <= 16 {
		time.Sleep(40 * time.Millisecond)
	}
	return c.Conn.Write(p)
}
func TestStreamingIsNotLimitedByOneChunkPerRTT(t *testing.T) {
	dial := func(context.Context) (net.Conn, error) {
		a, b := net.Pipe()
		go Serve(context.Background(), controlDelayConn{b}, func() bool { return true })
		return a, nil
	}
	result, err := Run(context.Background(), dial, func() bool { return true })
	if err != nil {
		t.Fatal(err)
	}
	if result.UploadBytesPerSecond < 1024*1024 || result.DownloadBytesPerSecond < 1024*1024 {
		t.Fatalf("stream limited by control RTT: %+v", result)
	}
}

type slowReadConn struct{ net.Conn }

func (c slowReadConn) Read(p []byte) (int, error) {
	time.Sleep(25 * time.Millisecond)
	if len(p) > 4096 {
		p = p[:4096]
	}
	return c.Conn.Read(p)
}
func TestSlowLinkReturnsPartialReceiverMeasurement(t *testing.T) {
	phase := 0
	dial := func(context.Context) (net.Conn, error) {
		phase++
		a, b := net.Pipe()
		if phase == 1 {
			go Serve(context.Background(), slowReadConn{b}, func() bool { return true })
		} else {
			go Serve(context.Background(), b, func() bool { return true })
		}
		return a, nil
	}
	result, err := Run(context.Background(), dial, func() bool { return true })
	if err != nil {
		t.Fatal(err)
	}
	if result.UploadBytesPerSecond < 10000 || result.UploadBytesPerSecond > 400000 {
		t.Fatalf("unexpected actual slow-link receiver rate: %+v", result)
	}
}

// TCP can buffer unread upload bytes when the receiver's phase deadline ends;
// unlike net.Pipe, closing then may reset the stream before its summary arrives.
func TestRealTCPSlowUploadReturnsReceiverMeasurement(t *testing.T) {
	phase := 0
	dial := func(ctx context.Context) (net.Conn, error) {
		phase++
		thisPhase := phase
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return nil, err
		}
		go func() {
			c, acceptErr := listener.Accept()
			listener.Close()
			if acceptErr != nil {
				return
			}
			if thisPhase == 1 {
				c.(*net.TCPConn).SetReadBuffer(4096)
				_ = Serve(context.Background(), slowReadConn{c}, func() bool { return true })
			} else {
				_ = Serve(context.Background(), c, func() bool { return true })
			}
		}()
		c, err := (&net.Dialer{}).DialContext(ctx, "tcp", listener.Addr().String())
		if err == nil && thisPhase == 1 {
			c.(*net.TCPConn).SetWriteBuffer(4096)
		}
		return c, err
	}
	result, err := Run(context.Background(), dial, func() bool { return true })
	if err != nil {
		t.Fatal(err)
	}
	if result.UploadBytesPerSecond <= 0 || result.DownloadBytesPerSecond <= 0 {
		t.Fatalf("missing measurements: %+v", result)
	}
}
