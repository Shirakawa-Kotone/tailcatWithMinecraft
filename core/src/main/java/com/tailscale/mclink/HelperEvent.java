package com.tailscale.mclink;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** NDJSON protocol envelope. Invitations are deliberately excluded from toString(). */
public final class HelperEvent {
    private final String type;
    private final String mode;
    private final String invite;
    private final String address;
    private final String code;
    private final String message;
    private final NetworkStatus stats;
    private final SpeedTestResult speedtest;

    public HelperEvent(String type, String mode, String invite, String address, String code, String message, NetworkStatus stats, SpeedTestResult speedtest) {
        this.type = type;
        this.mode = mode;
        this.invite = invite;
        this.address = address;
        this.code = code;
        this.message = message;
        this.stats = stats;
        this.speedtest = speedtest;
    }
    public String type() { return type; }
    public String mode() { return mode; }
    public String invite() { return invite; }
    public String address() { return address; }
    public String code() { return code; }
    public String message() { return message; }
    public NetworkStatus stats() { return stats; }
    public SpeedTestResult speedtest() { return speedtest; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HelperEvent)) return false;
        HelperEvent that = (HelperEvent) other;
        return java.util.Objects.equals(type, that.type) &&
                java.util.Objects.equals(mode, that.mode) &&
                java.util.Objects.equals(invite, that.invite) &&
                java.util.Objects.equals(address, that.address) &&
                java.util.Objects.equals(code, that.code) &&
                java.util.Objects.equals(message, that.message) &&
                java.util.Objects.equals(stats, that.stats) &&
                java.util.Objects.equals(speedtest, that.speedtest);
    }
    @Override public int hashCode() {
        int result = 0;
        result = 31 * result + java.util.Objects.hashCode(type);
        result = 31 * result + java.util.Objects.hashCode(mode);
        result = 31 * result + java.util.Objects.hashCode(invite);
        result = 31 * result + java.util.Objects.hashCode(address);
        result = 31 * result + java.util.Objects.hashCode(code);
        result = 31 * result + java.util.Objects.hashCode(message);
        result = 31 * result + java.util.Objects.hashCode(stats);
        result = 31 * result + java.util.Objects.hashCode(speedtest);
        return result;
    }

    public static final int MAX_LINE_LENGTH = 65_536;
    private static final Set<String> TYPES = Compatibility.immutableSet("ready", "error", "stopped", "stats", "speedtest");
    private static final Pattern LOOPBACK = Pattern.compile("127\\.0\\.0\\.1:([0-9]{1,5})");
    public HelperEvent(String type, String mode, String invite, String address, String code, String message) {
        this(type, mode, invite, address, code, message, null, null);
    }
    public static HelperEvent parse(String line) throws IOException {
        try {
            if (line == null || line.length() > MAX_LINE_LENGTH) throw JsonFields.invalid();
            JsonObject o = new JsonParser().parse(line).getAsJsonObject();
            String type = JsonFields.required(o, "type", 32);
            if (!TYPES.contains(type)) throw JsonFields.invalid();
            Long protocol = JsonFields.integer(o, "protocolVersion");
            if ((protocol != null && protocol != 1) || (Compatibility.immutableSet("ready", "stats", "speedtest").contains(type) && protocol == null))
                throw new IOException("Unsupported helper protocol version");
            String mode = JsonFields.string(o, "mode", 16);
            String invite = JsonFields.string(o, "invite", 32_768);
            String address = JsonFields.string(o, "address", 128);
            if ("ready".equals(type)) {
                if (!Compatibility.immutableSet("host", "join").contains(mode == null ? "" : mode)) throw JsonFields.invalid();
                if ("host".equals(mode) && (invite == null || Compatibility.isBlank(invite))) throw JsonFields.invalid();
                if ("join".equals(mode)) {
                    Matcher matcher = LOOPBACK.matcher(address == null ? "" : address);
                    if (!matcher.matches()) throw JsonFields.invalid();
                    int port = Integer.parseInt(matcher.group(1));
                    if (port < 1 || port > 65535) throw JsonFields.invalid();
                }
            }
            return new HelperEvent(type, mode, invite, address, JsonFields.string(o, "code", 128),
                    JsonFields.string(o, "message", 2048), "stats".equals(type) ? NetworkStatus.parse(JsonFields.object(o, "stats")) : null,
                    "speedtest".equals(type) ? SpeedTestResult.parse(JsonFields.object(o, "speedtest")) : null);
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("Malformed helper IPC event"); }
    }
    @Override public String toString() { return "HelperEvent[type=" + type + ", mode=" + mode + "]"; }
}
