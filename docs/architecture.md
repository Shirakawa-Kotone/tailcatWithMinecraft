# Tailcat Minecraft architecture

Approved direction: shared Java core, Minecraft/version/loader adapters and bundled Go helper. User additions: `/tc-status` reports actual UDP/DERP path, relay, rates and versions; `/tc-speedtest` runs a bounded active throughput/latency test.

## Scope

- Java Edition client mod, integrated-world sharing and invitation joining.
- First matrix: 1.20.1 Fabric/Forge; 1.21.1 Fabric/NeoForge, independently built JARs.
- Bundled Windows/macOS/Linux helpers; no player installation of Tailscale or Tailcat.
- Retain normal Minecraft authentication. Invitations authorize network access, not player identity.
- No protocol translation between incompatible Minecraft versions or modpacks.

## Boundaries

- `core`: Java 17 compatible helper process, native extraction, IPC events, status parsing/formatting and session lifecycle; no Minecraft or loader references.
- Shared/version-specific Minecraft client source: menu screens, integrated server publishing, connect flow, client-thread scheduling.
- Loader modules: client initialization, lifecycle, client-local command registration and metadata.
- `helper`: pinned Tailcat Go library; one host or join session per process; allowed virtual game port 25565; loopback-only forwarding and bounded streams.
- Native payloads share a release version independent of the Minecraft loader build. Build artifacts contain them with SHA-256 manifest.

## IPC and status contract

NDJSON events on helper stdout; stderr is diagnostics. Existing `ready`, `error`, `stopped` events are retained. New `stats` events contain `stats` object and are emitted about once per second; `ready`/`stats` carry protocol version 1. Stats fields use JSON camelCase: `mode`, `path` (`udp`, `derp`, `mixed`, `unknown`, `idle`), `relay`, `activeConnections`, `txBytes`, `rxBytes`, `txBytesPerSecond`, `rxBytesPerSecond`, `peakTxBytesPerSecond`, `peakRxBytesPerSecond`, `uptimeSeconds`, `helperVersion`, `tailcatVersion`. Optional peer/latency fields may be added compatibly. Rates count proxied Minecraft payload bytes from this process's perspective, bytes/second; peaks are sampled session peaks, not a bandwidth benchmark. Missing network observations are unknown, never assumed to be DERP/direct. Public DERP capacity is not a known maximum speed.

`/tc-status` is intercepted locally, including on servers that do not install this mod. It displays session state, role, observed path, bootstrap/active relay with clear labels, peers/connections, upload/download and sampled peaks, uptime, and mod/helper/Tailcat/MC/loader versions. Idle and stale data must be distinguishable; no invitation/private key appears in output or logs.

## Lifecycle

## Active speed test

`/tc-speedtest` runs asynchronously through the existing helper, using a dedicated virtual port 25566 handled by the helper, never forwarded into Minecraft. Commands on helper stdin are NDJSON (`{"type":"speedtest"}`); results are `{"type":"speedtest","speedtest":{...}}` with `state` (`running`, `complete`, `error`), `uploadBytesPerSecond`, `downloadBytesPerSecond`, optional `latencyMs`, `message`. One test per process; bounded duration and bytes. It measures this session's achievable throughput, not guaranteed maximum capacity. Default policy refuses tests over shared/public DERP relays, permitting direct paths; custom relay testing requires explicit controlled configuration. No automated throughput test against third-party public relays. Test traffic is separate from gameplay throughput counters. Cancellation/session closure must stop tests and stale results must not reach a new session.

## Lifecycle

Share publishes the integrated server using normal LAN behavior and starts the helper against its real loopback port. Join starts a helper using an invitation and connects Minecraft to its random loopback port after readiness. Session generation guards prevent late callbacks from affecting a replacement session. Cancellation, world exit, game shutdown, malformed IPC, startup failure and EOF clean up listeners/processes. Closing must not block the render thread.

## Validation

Java unit tests for malformed events, rates/status formatting and lifecycle; Go tests for invitations, counters and cancellation; compile/package each matrix artifact; verify bundled native manifests. Real two-machine UDP/DERP, account authentication, multi-visitor and OS signing checks are separate manual acceptance items.
