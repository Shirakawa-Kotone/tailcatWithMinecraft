package com.tailscale.mclink;

import java.util.List;
import java.util.Collections;

/** Immutable client-thread view of hosting and local world player observations. */
public final class ShareView {
    public enum Phase { OFF, STARTING, ON, STOPPING, FAILED }
    private final Phase phase;
    private final String invitation, failure;
    private final NetworkStatus network;
    private final List<String> players;
    private final boolean playersKnown;

    private ShareView(State state) {
        phase = state.phase; invitation = state.invitation; failure = state.failure;
        network = state.network; players = state.players; playersKnown = state.playersKnown;
    }
    public Phase phase() { return phase; }
    public String invitation() { return invitation; }
    public NetworkStatus network() { return network; }
    public List<String> players() { return players; }
    public boolean playersKnown() { return playersKnown; }
    public String failure() { return failure; }

    /** Internal client-thread state; public because platform families use separate class loaders. */
    public static final class State {
        private Object token, world;
        private Phase phase = Phase.OFF;
        private String invitation, failure;
        private NetworkStatus network = NetworkStatus.idle();
        private List<String> players = Collections.emptyList();
        private boolean playersKnown;

        public void starting(Object token) {
            this.token = token;
            phase = Phase.STARTING; invitation = null; failure = null;
            network = NetworkStatus.waiting("host");
        }
        public void ready(Object token, String invitation) {
            if (this.token != token || phase != Phase.STARTING) return;
            this.invitation = invitation; phase = Phase.ON;
        }
        public void network(Object token, NetworkStatus status) {
            if (this.token == token && (phase == Phase.STARTING || phase == Phase.ON)) network = status;
        }
        public void stopping(Object token) {
            if (this.token != token || (phase != Phase.STARTING && phase != Phase.ON)) return;
            phase = Phase.STOPPING; invitation = null;
        }
        public void stopped() {
            if (phase != Phase.STOPPING) return;
            token = null; phase = Phase.OFF; network = NetworkStatus.idle();
        }
        public void failed(Object token) {
            if (this.token == token && (phase == Phase.STARTING || phase == Phase.ON)) failedToStart();
        }
        public void failedToStart() {
            token = null; phase = Phase.FAILED; invitation = null;
            network = NetworkStatus.idle(); failure = "Sharing failed";
        }
        public void world(Object world) {
            if (this.world == world) return;
            this.world = world; players = Collections.emptyList(); playersKnown = false;
        }
        public void players(Object world, List<String> players) {
            if (world == null || this.world != world) return;
            this.players = Compatibility.immutableListCopy(players); playersKnown = true;
        }
        public ShareView snapshot() { return new ShareView(this); }
    }
}
