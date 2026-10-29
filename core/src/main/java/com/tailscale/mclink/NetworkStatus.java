package com.tailscale.mclink;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Immutable observations. Null numeric fields mean unobserved, never zero. */
public final class NetworkStatus {
    private final String mode;
    private final String path;
    private final String relay;
    private final Long activeConnections;
    private final Long txBytes;
    private final Long rxBytes;
    private final Double txBytesPerSecond;
    private final Double rxBytesPerSecond;
    private final Double peakTxBytesPerSecond;
    private final Double peakRxBytesPerSecond;
    private final Double uptimeSeconds;
    private final String helperVersion;
    private final String tailcatVersion;
    private final Instant receivedAt;
    private final String bootstrapRelay;
    private final Double latencyMs;
    private final List<PeerStatus> peers;
    private final Long omittedPeers;

    public NetworkStatus(String mode, String path, String relay, Long activeConnections, Long txBytes, Long rxBytes, Double txBytesPerSecond, Double rxBytesPerSecond, Double peakTxBytesPerSecond, Double peakRxBytesPerSecond, Double uptimeSeconds, String helperVersion, String tailcatVersion, Instant receivedAt, String bootstrapRelay, Double latencyMs, List<PeerStatus> peers, Long omittedPeers) {
        this.mode = mode;
        this.path = path;
        this.relay = relay;
        this.activeConnections = activeConnections;
        this.txBytes = txBytes;
        this.rxBytes = rxBytes;
        this.txBytesPerSecond = txBytesPerSecond;
        this.rxBytesPerSecond = rxBytesPerSecond;
        this.peakTxBytesPerSecond = peakTxBytesPerSecond;
        this.peakRxBytesPerSecond = peakRxBytesPerSecond;
        this.uptimeSeconds = uptimeSeconds;
        this.helperVersion = helperVersion;
        this.tailcatVersion = tailcatVersion;
        this.receivedAt = receivedAt;
        this.bootstrapRelay = bootstrapRelay;
        this.latencyMs = latencyMs;
        this.peers = peers == null ? null : Compatibility.immutableListCopy(peers);
        this.omittedPeers = omittedPeers;
    }
    public String mode() { return mode; }
    public String path() { return path; }
    public String relay() { return relay; }
    public Long activeConnections() { return activeConnections; }
    public Long txBytes() { return txBytes; }
    public Long rxBytes() { return rxBytes; }
    public Double txBytesPerSecond() { return txBytesPerSecond; }
    public Double rxBytesPerSecond() { return rxBytesPerSecond; }
    public Double peakTxBytesPerSecond() { return peakTxBytesPerSecond; }
    public Double peakRxBytesPerSecond() { return peakRxBytesPerSecond; }
    public Double uptimeSeconds() { return uptimeSeconds; }
    public String helperVersion() { return helperVersion; }
    public String tailcatVersion() { return tailcatVersion; }
    public Instant receivedAt() { return receivedAt; }
    public String bootstrapRelay() { return bootstrapRelay; }
    public Double latencyMs() { return latencyMs; }
    public List<PeerStatus> peers() { return peers; }
    public Long omittedPeers() { return omittedPeers; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof NetworkStatus)) return false;
        NetworkStatus that = (NetworkStatus) other;
        return java.util.Objects.equals(mode, that.mode) &&
                java.util.Objects.equals(path, that.path) &&
                java.util.Objects.equals(relay, that.relay) &&
                java.util.Objects.equals(activeConnections, that.activeConnections) &&
                java.util.Objects.equals(txBytes, that.txBytes) &&
                java.util.Objects.equals(rxBytes, that.rxBytes) &&
                java.util.Objects.equals(txBytesPerSecond, that.txBytesPerSecond) &&
                java.util.Objects.equals(rxBytesPerSecond, that.rxBytesPerSecond) &&
                java.util.Objects.equals(peakTxBytesPerSecond, that.peakTxBytesPerSecond) &&
                java.util.Objects.equals(peakRxBytesPerSecond, that.peakRxBytesPerSecond) &&
                java.util.Objects.equals(uptimeSeconds, that.uptimeSeconds) &&
                java.util.Objects.equals(helperVersion, that.helperVersion) &&
                java.util.Objects.equals(tailcatVersion, that.tailcatVersion) &&
                java.util.Objects.equals(receivedAt, that.receivedAt) &&
                java.util.Objects.equals(bootstrapRelay, that.bootstrapRelay) &&
                java.util.Objects.equals(latencyMs, that.latencyMs) &&
                java.util.Objects.equals(peers, that.peers) &&
                java.util.Objects.equals(omittedPeers, that.omittedPeers);
    }
    @Override public int hashCode() {
        int result = 0;
        result = 31 * result + java.util.Objects.hashCode(mode);
        result = 31 * result + java.util.Objects.hashCode(path);
        result = 31 * result + java.util.Objects.hashCode(relay);
        result = 31 * result + java.util.Objects.hashCode(activeConnections);
        result = 31 * result + java.util.Objects.hashCode(txBytes);
        result = 31 * result + java.util.Objects.hashCode(rxBytes);
        result = 31 * result + java.util.Objects.hashCode(txBytesPerSecond);
        result = 31 * result + java.util.Objects.hashCode(rxBytesPerSecond);
        result = 31 * result + java.util.Objects.hashCode(peakTxBytesPerSecond);
        result = 31 * result + java.util.Objects.hashCode(peakRxBytesPerSecond);
        result = 31 * result + java.util.Objects.hashCode(uptimeSeconds);
        result = 31 * result + java.util.Objects.hashCode(helperVersion);
        result = 31 * result + java.util.Objects.hashCode(tailcatVersion);
        result = 31 * result + java.util.Objects.hashCode(receivedAt);
        result = 31 * result + java.util.Objects.hashCode(bootstrapRelay);
        result = 31 * result + java.util.Objects.hashCode(latencyMs);
        result = 31 * result + java.util.Objects.hashCode(peers);
        result = 31 * result + java.util.Objects.hashCode(omittedPeers);
        return result;
    }
    @Override public String toString() { return "NetworkStatus[mode=" + mode + ", path=" + path + ", relay=" + relay + ", activeConnections=" + activeConnections + ", txBytes=" + txBytes + ", rxBytes=" + rxBytes + ", txBytesPerSecond=" + txBytesPerSecond + ", rxBytesPerSecond=" + rxBytesPerSecond + ", peakTxBytesPerSecond=" + peakTxBytesPerSecond + ", peakRxBytesPerSecond=" + peakRxBytesPerSecond + ", uptimeSeconds=" + uptimeSeconds + ", helperVersion=" + helperVersion + ", tailcatVersion=" + tailcatVersion + ", receivedAt=" + receivedAt + ", bootstrapRelay=" + bootstrapRelay + ", latencyMs=" + latencyMs + ", peers=" + peers + ", omittedPeers=" + omittedPeers + "]"; }

    private static final Set<String> PATHS = Compatibility.immutableSet("udp", "derp", "mixed", "unknown", "idle");


    /** Compatibility constructor for snapshots without optional transport observations. */
    public NetworkStatus(String mode, String path, String relay, Long activeConnections,
            Long txBytes, Long rxBytes, Double txBytesPerSecond, Double rxBytesPerSecond,
            Double peakTxBytesPerSecond, Double peakRxBytesPerSecond, Double uptimeSeconds,
            String helperVersion, String tailcatVersion, Instant receivedAt) {
        this(mode, path, relay, activeConnections, txBytes, rxBytes, txBytesPerSecond, rxBytesPerSecond,
                peakTxBytesPerSecond, peakRxBytesPerSecond, uptimeSeconds, helperVersion, tailcatVersion,
                receivedAt, null, null, null, null);
    }
    public static final class PeerStatus {
        private final String id;
        private final String path;
        private final String relay;

        public PeerStatus(String id, String path, String relay) {
            this.id = id;
            this.path = path;
            this.relay = relay;
        }
        public String id() { return id; }
        public String path() { return path; }
        public String relay() { return relay; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof PeerStatus)) return false;
            PeerStatus that = (PeerStatus) other;
            return     java.util.Objects.equals(id, that.id) &&
                    java.util.Objects.equals(path, that.path) &&
                    java.util.Objects.equals(relay, that.relay);
        }
        @Override public int hashCode() {
            int result = 0;
            result = 31 * result + java.util.Objects.hashCode(id);
            result = 31 * result + java.util.Objects.hashCode(path);
            result = 31 * result + java.util.Objects.hashCode(relay);
            return result;
        }
        @Override public String toString() { return "PeerStatus[id=" + id + ", path=" + path + ", relay=" + relay + "]"; }
    }

    public static NetworkStatus idle() { return waiting(null); }
    public static NetworkStatus waiting(String mode) {
        return new NetworkStatus(mode, mode == null ? "idle" : "unknown", null, null, null, null,
                null, null, null, null, null, null, null, null);
    }
    public static NetworkStatus parse(JsonObject o) throws IOException {
        String mode = JsonFields.string(o, "mode", 16);
        if (mode != null && !Compatibility.immutableSet("host", "join").contains(mode)) throw JsonFields.invalid();
        return new NetworkStatus(mode, path(o),
                JsonFields.string(o, "relay", 512), JsonFields.integer(o, "activeConnections"),
                JsonFields.integer(o, "txBytes"), JsonFields.integer(o, "rxBytes"),
                JsonFields.decimal(o, "txBytesPerSecond"), JsonFields.decimal(o, "rxBytesPerSecond"),
                JsonFields.decimal(o, "peakTxBytesPerSecond"), JsonFields.decimal(o, "peakRxBytesPerSecond"),
                JsonFields.decimal(o, "uptimeSeconds"), JsonFields.string(o, "helperVersion", 128),
                JsonFields.string(o, "tailcatVersion", 128), Instant.now(),
                JsonFields.string(o, "bootstrapRelay", 512), JsonFields.decimal(o, "latencyMs"), peers(o),
                JsonFields.integer(o, "omittedPeers"));
    }
    private static String path(JsonObject o) throws IOException {
        String path = JsonFields.string(o, "path", 16);
        if (path != null && !PATHS.contains(path)) throw JsonFields.invalid();
        return path == null ? "unknown" : path;
    }
    private static List<PeerStatus> peers(JsonObject o) throws IOException {
        JsonElement peers = o.get("peers");
        if (peers == null || peers.isJsonNull()) return null;
        if (!peers.isJsonArray() || peers.getAsJsonArray().size() > 256) throw JsonFields.invalid();
        List<PeerStatus> observations = new ArrayList<>();
        for (JsonElement element : peers.getAsJsonArray()) {
            if (!element.isJsonObject()) throw JsonFields.invalid();
            JsonObject peer = element.getAsJsonObject();
            observations.add(new PeerStatus(JsonFields.required(peer, "id", 128), path(peer), JsonFields.string(peer, "relay", 512)));
        }
        return Compatibility.immutableListCopy(observations);
    }
    public static NetworkStatus fromJson(JsonObject o) throws IOException { return parse(o); }
    public boolean isStale() { return isStale(Instant.now()); }
    public boolean isStale(Instant now) {
        return receivedAt != null && Duration.between(receivedAt, now).compareTo(Duration.ofSeconds(5)) > 0;
    }
}
