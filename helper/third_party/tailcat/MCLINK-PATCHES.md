# Tailcat source provenance

Source copied from the public https://github.com/tailscale/tailcat commit
`fe50bc39d802506ec08a3e9210a02b3a6da1f29f` (2026-08-26), under the adjacent
BSD 3-Clause LICENSE. This revision exposes Client.DiscoPing for observed
UDP/DERP path information and uses the existing Go 1.26.5 toolchain.

The local production changes are compatible upstream backports. Peer status uses
`15ab9e68bfc6534a61797d7af28cedd42b54a3a5` (2026-09-17):

```diff
- var sb ipnstate.StatusBuilder
+ sb := ipnstate.StatusBuilder{WantPeers: true}
```

This enables the existing public Server.Status peer observations. Without it,
magicsock/wgengine omit peer entries. No log parsing or private reflection is
used. Upgrade the pin and remove this backport when the native toolchain is
updated to support newer Tailcat.

Backend shutdown additionally backports `e9f786eea140d8e4700e7e1a525414e4c5cd4c7e`
(2026-08-28): idempotent Close shuts down netstack, engine, netmon, Dialer and
eventbus. Constructor error branches call that Close, adapting the cleanup
principle from `7c2a6ea6042907e1b2cec40fc85efdebdbdefcf5` to this older
NewServer/NewClient API. These changes prevent process/session cleanup from
leaving background resources behind.
