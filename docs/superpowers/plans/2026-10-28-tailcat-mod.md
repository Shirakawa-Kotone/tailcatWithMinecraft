# Tailcat mod implementation plan

> **For agentic workers:** Use the approved architecture in docs/architecture.md. Execute continuously; the user explicitly authorized implementation. Keep independent work in disjoint directories and review the integrated result.

**Goal:** Deliver the initial four Minecraft/loader builds with bundled Tailcat and `/tc-status`.

**Architecture:** Reuse the BSD upstream helper and game flow. Isolate Java runtime/status logic from Minecraft and loader APIs; expose real network telemetry through NDJSON.

**Tech Stack:** Java 17/21, Gradle, Fabric Loom, ForgeGradle, NeoForge tooling, Go/Tailcat, JUnit.

**Spec:** docs/architecture.md

## Global constraints

- First matrix: Minecraft 1.20.1 Fabric/Forge, 1.21.1 Fabric/NeoForge.
- Client-local `/tc-status`; no fabricated throughput capacity or path data.
- Bundle helpers by default, preserve upstream notices, no player runtime download.
- Keep normal Minecraft authentication; only virtual game port 25565; loopback forwarding.
- No unrelated external writes, publishing or pushing.

## Review focus

- Multi-client sessions and cancellation must not leak forwarding sockets.
- Old callbacks must not stop or connect a replacement session.
- Status must label unknown/stale observations and session peaks accurately.
- Client commands must not be sent to a remote Minecraft server.
- Every matrix artifact must include the correct metadata and native resources.

## Tasks

1. [ ] Import pinned upstream after writing reuse report; preserve BSD LICENSE; index local code; establish build environment.
2. [ ] Extend Go helper telemetry using actual public transport observations, tested byte counters, bounded forwarding and shutdown. Verify `go test ./...`, race tests and native builds.
3. [ ] Extract Java core runtime and implement validated status events, immutable snapshots, human-readable status lines and lifecycle protections. Verify Java tests with actual helper IPC fixture.
4. [ ] Add game adapters and loader entrypoints/commands; compile all four variants, keeping version differences explicit.
5. [ ] Bundle platform helpers, add CI and Chinese usage/build docs; review and run full verification, inspect final JAR contents.

## Shared interfaces

- Helper events and rate units are fixed by docs/architecture.md.
- Java core retains package `com.tailscale.mclink`; platform-neutral `NativeHelper.extract(Path gameDirectory, String modVersion, boolean development)` and `HelperProcess.start(List<String>, Path, String, boolean, Consumer<HelperEvent>)`.
- `NetworkStatus` is a snapshot parsed from `stats`; `StatusFormatter.lines(NetworkStatus, String modVersion, String minecraftVersion, String loader)` returns `List<String>`.
- Game adapters consume `HelperProcess.ready(Duration)`, `HelperProcess.isAlive()`, events and asynchronous `close()`; loader command handlers render formatted lines locally.

## Verification commands

`./gradlew :core:test`; `go test ./...` in helper with the pinned Tailscale Go toolchain; `./gradlew build`; `scripts/verify-artifacts.py`.
