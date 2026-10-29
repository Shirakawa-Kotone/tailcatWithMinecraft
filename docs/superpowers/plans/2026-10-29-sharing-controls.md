# Sharing controls and compact invitations

Scope: preserve vanilla LAN access, make hosting controls and online players explicit, and accept compact invitations without an external service. Keep all 38 advertised adapter combinations and offline bundled native helpers.

- Preserve the original unpublished-world LAN menu. Once Minecraft publishes the world and replaces/disables its LAN control, offer a separate LAN status entry with the current port.
- Opening the Tailcat screen only observes the current session. Explicitly start, cancel startup, stop, copy, or return. Returning keeps the session alive; reopening reuses its invitation.
- Share one immutable core presentation model for OFF, STARTING, ON, STOPPING and FAILED. Reject stale session callbacks. Show actual network observations and flag stale data.
- Read integrated-server player names on its executor, return snapshots to the client, reject old-world responses, and paginate the list. Label it as the world roster including LAN players.
- Use the opaque native `tc` token as the short code. Convert it to the existing `mcl1_` envelope before helper startup. Keep a legacy-copy button and validate envelope boundaries. No key or native protocol changes.
- Verify real Java 8 core behavior, loader isolation, all adapter builds, seven packaged profiles, and a real isolated 1.21.6 startup. Preserve logs, publish from local artifacts, then clean project caches. Actual multi-machine game acceptance remains separate from compilation and startup checks.
