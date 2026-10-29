package com.tailscale.mclink;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SpeedTestResult {
    private final String state;
    private final Double uploadBytesPerSecond;
    private final Double downloadBytesPerSecond;
    private final Double latencyMs;
    private final String message;

    public SpeedTestResult(String state, Double uploadBytesPerSecond, Double downloadBytesPerSecond, Double latencyMs, String message) {
        this.state = state;
        this.uploadBytesPerSecond = uploadBytesPerSecond;
        this.downloadBytesPerSecond = downloadBytesPerSecond;
        this.latencyMs = latencyMs;
        this.message = message;
    }
    public String state() { return state; }
    public Double uploadBytesPerSecond() { return uploadBytesPerSecond; }
    public Double downloadBytesPerSecond() { return downloadBytesPerSecond; }
    public Double latencyMs() { return latencyMs; }
    public String message() { return message; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SpeedTestResult)) return false;
        SpeedTestResult that = (SpeedTestResult) other;
        return java.util.Objects.equals(state, that.state) &&
                java.util.Objects.equals(uploadBytesPerSecond, that.uploadBytesPerSecond) &&
                java.util.Objects.equals(downloadBytesPerSecond, that.downloadBytesPerSecond) &&
                java.util.Objects.equals(latencyMs, that.latencyMs) &&
                java.util.Objects.equals(message, that.message);
    }
    @Override public int hashCode() {
        int result = 0;
        result = 31 * result + java.util.Objects.hashCode(state);
        result = 31 * result + java.util.Objects.hashCode(uploadBytesPerSecond);
        result = 31 * result + java.util.Objects.hashCode(downloadBytesPerSecond);
        result = 31 * result + java.util.Objects.hashCode(latencyMs);
        result = 31 * result + java.util.Objects.hashCode(message);
        return result;
    }
    @Override public String toString() { return "SpeedTestResult[state=" + state + ", uploadBytesPerSecond=" + uploadBytesPerSecond + ", downloadBytesPerSecond=" + downloadBytesPerSecond + ", latencyMs=" + latencyMs + ", message=" + message + "]"; }

    public static SpeedTestResult parse(JsonObject o) throws IOException {
        String state = JsonFields.required(o, "state", 16);
        if (!Compatibility.immutableSet("running", "complete", "error").contains(state)) throw JsonFields.invalid();
        return new SpeedTestResult(state, JsonFields.decimal(o, "uploadBytesPerSecond"),
                JsonFields.decimal(o, "downloadBytesPerSecond"), JsonFields.decimal(o, "latencyMs"),
                JsonFields.string(o, "message", 1024));
    }
    public List<String> lines() {
        if ("running".equals(state)) return Compatibility.immutableList("Tailcat 测速进行中（有时长和流量上限）…");
        if ("error".equals(state)) return Compatibility.immutableList("Tailcat 测速未完成：" + StatusFormatter.known(message));
        return Compatibility.immutableList("Tailcat 本次会话测速（实测吞吐量，不保证最大容量）", "上传：" + StatusFormatter.rate(uploadBytesPerSecond)
                + "；下载：" + StatusFormatter.rate(downloadBytesPerSecond), "延迟：" + (latencyMs == null ? "未知" : String.format(Locale.ROOT, "%.2f ms", latencyMs)));
    }
}
