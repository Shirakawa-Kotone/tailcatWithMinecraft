# Tailcat Minecraft reuse decision

Date: 2026-10-28. Scope: Minecraft Java Edition client mod; bundled Tailcat; Fabric/Forge/NeoForge across selected Minecraft versions; `/tc-status` diagnostics.

## Decision

Use `tailscale/tailcat-for-minecraft` at `c561a896f3e48c98bde36b0a810b29f560b074a5` as the upstream, BSD-3-Clause. Preserve LICENSE and attribution. The repository is explicitly an unsupported experimental prototype, not a production-ready distribution.

Repository: https://github.com/tailscale/tailcat-for-minecraft

Reuse its Go helper, invitation envelope, native extraction/checksum approach, and share/join interaction. Refactor Minecraft and loader dependencies behind adapters; add measured telemetry and client commands. Keep the actual Tailcat implementation as a pinned dependency instead of reimplementing its transport.

## Candidates considered

| Candidate | Fit | Decision |
| --- | --- | --- |
| tailscale/tailcat-for-minecraft | Fabric 1.21.1; bundled helper; share/join already implemented; BSD-3-Clause | Upstream host; extend and verify |
| tailscale/tailcat | Go userspace WireGuard/NAT traversal/DERP library; BSD-3-Clause | Helper dependency; lock version |
| Gaming32/world-host | World sharing and multiplayer UI; MIT | Reference only: different networking/control-service architecture |
| Marker284/tailmod | Tailscale/tsnet integration, native packaging; license not verified | Excluded: Tailnet model differs from Tailcat invitations |
| Architectury | Common/platform project layout | Optional build reference; avoid mandatory runtime API for small lifecycle surface |
| Stonecutter | Minecraft version source preprocessing | Candidate for later larger matrices; initial explicit version source sets avoid coupling build plugins |

Evidence: upstream README and LICENSE inspected locally after clone; project indexed through codebase-memory before source exploration. Runtime verification is recorded in the implementation ledger; README claims alone are not verification. No candidate copies beyond the selected BSD upstream are incorporated.

## Required upstream follow-up

## Universal/range extension (2026-10-29)

Retain the existing Tailcat implementation and the verified version adapters. Reuse pure-Java `org.tukaani:xz:1.12` (public-domain license) for a solid tar.xz containing the six native executables; measured all-platform payload is about17MiB, versus43MiB for separate ZIP members. No native packer or player-time download is required.

The universal package adds small Java8 loader entrypoints and standard-library URLClassLoader isolation of the selected nested adapter. Existing loader-specific remapped outputs are preserved; no source-level reimplementation of Minecraft/loader behavior and no assumption that Forge/Fabric mapping namespaces are interchangeable. SDK interface/annotation declarations are compile-only and excluded from shipped classes. All supported combinations must appear in the verified build index; unavailable loaders are N/A.

For the10MB preference, additionally offer one-platform offline native profiles rather than silently removing platforms from the universal package. Universal distribution is guarded at50,000,000 bytes.

Native signing/notarization before public release; complete Go dependency notices; multiple peers, authentication and direct/DERP behavior on separate machines. Native helpers must be bundled in normal artifacts, not fetched at player runtime.

## Sharing controls and shorter invitations (2026-10-29)

Reuse the vendored Tailcat native `tc` invitation format and existing helper version-1 envelope. The shorter code preserves all opaque connection bytes; only the redundant wrapper is removed for copying and restored before helper invocation. No cryptographic change, dependency upgrade or external short-link database is required. Very short lookup codes would need a separately operated service and are outside this implementation. Vanilla LAN publishing and the existing server executor remain the sources of truth for the port and player roster.
