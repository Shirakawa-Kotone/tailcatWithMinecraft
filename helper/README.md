# Bundled Minecraft helper

`mclink-helper host --target 127.0.0.1:PORT` shares a numeric loopback
Minecraft listener. `join --invite mcl1_...` exposes a random numeric loopback
listener. `version` prints a JSON version event without starting networking.
Never store or log invitation strings: they authorize access to the session.
Each process creates ephemeral Tailcat keys.

Stdout is protocol version 1 NDJSON. Stdin accepts `{"type":"speedtest"}` and
`{"type":"cancel_speedtest"}`. EOF, signals and session cancellation close
listeners, pending dials, active streams, tests and Tailcat resources. A process
admits at most 64 pending/active streams. Only virtual game port 25565 is
forwarded; measurement port 25566 is handled internally.

Stats events arrive about once per second. Rates count bytes successfully
written by the Minecraft payload proxy from this process's network perspective;
encryption overhead, discovery traffic and test traffic are excluded. Peaks are
sampled session peaks. They do not estimate maximum bandwidth. Host observations
use public Server.Status peer entries; joining clients use public DiscoPing
results. Peer CurAddr plus active status means UDP, active peer Relay with no
direct endpoint means DERP. Bootstrap relay selection is labeled separately.
Absent/failed observations mean unknown; no game connections mean idle. Mixed
hosts include per-peer observed paths. Peer relay observations are unknown,
not classified as direct UDP. Logs are never parsed for status.

A joining client can request an asynchronous speed test. A host's local request
explains that the joining client must initiate it. Each direction uses a separate
internal measurement TCP connection and streams at most 8 MiB for at most two
seconds, with a six-second overall protocol deadline. Upload is measured at the
host receiver (its returned byte count and duration), download at the joining
receiver. Slow links return partial measurements. There is one handshake ACK per
direction, rather than a throughput-limiting ACK per chunk. Latency is protocol
handshake RTT, not ICMP latency. These are session measurements, not guaranteed
network capacity.

Tests require a fresh observed direct path before opening the measurement
protocol. The client refreshes discovery observations every 100 ms during the
test; both endpoints monitor their accepted peer's path and abort on unknown,
relay or cancellation. Host authorization matches the accepted virtual peer
address to the public peer key, so another direct visitor cannot authorize a
relayed visitor's test. Shared/public DERP tests and custom-relay overrides are
not supported. A path can transition between samples; detection has a bounded
polling delay and in-flight traffic may already exist before cancellation.

Tailcat is pinned and vendored in `third_party/tailcat`; see its LICENSE and
MCLINK-PATCHES.md for public source revisions and the small status/resource
cleanup backports. Build with the patched Tailscale Go toolchain configured by
`../scripts/go-env.sh`, and use the `ts_omit_ssh` build tag. Tests:

```sh
# From the repository root, with bash:
TC_GOPATH=/private/tmp/mclink-gopath
export TC_GOPATH
source scripts/go-env.sh
cd helper
"$TC_GO" test -tags=ts_omit_ssh ./...
CGO_ENABLED=1 "$TC_GO" test -race -tags=ts_omit_ssh ./...
```

Unit/in-memory tests exercise rate accounting, sampled peaks, bounded streams,
strict loopback validation, malformed commands, EOF cancellation, actual peer
path authorization, throughput streaming, slow-link partial results and test
cancellation. Real two-machine NAT traversal, relay fallback and OS signing
remain manual acceptance checks. No test benchmarks a public DERP relay.
