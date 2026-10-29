# Universal package and version range implementation plan

**Goal:** Extend the mod through the requested Minecraft range, keep fully offline bundled Tailcat, offer a universal package below50,000,000 bytes and platform-specific packages ideally below10,000,000 bytes, clean project build caches and update README.

**Architecture:** Keep each compiled loader/version adapter in an isolated nested JAR. Three small Java8 bootstraps select exactly one adapter using runtime game version; selected adapter loads through a child classloader with the game mod loader as parent. This prevents old Forge scanners from reading Java25 classes and avoids mapping/package collisions. Shared Java8 core and the compressed native payload appear once in the outer JAR. No runtime download.

**Existing verified work:** Solid tar.xz native compression reduced ~43MiB to~17MiB; six variants including Fabric/NeoForge26.3 build. Source/doc state is the recovery authority.

## Constraints

- Actual compile verification per advertised supported version/loader; no widening metadata to claim untested compatibility.
- NeoForge before its existence is N/A.
-26.x uses NoRemap tooling, no obfuscation mappings.
- Universal size guard50,000,000 bytes; slim platform package target10,000,000 bytes.
- Only project-owned caches are deleted; retain final dist artifacts and test evidence.
- Existing status/speedtest, secret handling and normal Minecraft authentication remain unchanged.

## Tasks

1. Port core to Java8/Gson2.8 API, keep tests and actual Java8 compilation.
2. Build representative stable-version Fabric adapters across API breakpoints; user may clarify full patch granularity asynchronously.
3. Build Forge/NeoForge where available, isolating FG6 on compatible Gradle8.
4. Implement Java8 loader bootstraps + integrity-checked nested adapter selection and universal packaging with one native payload.
5. Add single-platform native archive profiles and size reports/guards.
6. Export all final artifacts to dist, then clean project-managed build caches automatically; preserve dependency/toolchain installs outside project.
7. Rewrite polished README with exact tested matrix, installation, size explanation and reproducible commands.
8. Review universal bootstraps, verify bytecode/metadata/nested hashes/extraction, run core/helper tests, package and verify all artifacts.
