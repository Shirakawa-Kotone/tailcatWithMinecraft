package com.tailscale.mclink;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class StatusFormatter {
    private StatusFormatter() {}
    public static List<String> lines(NetworkStatus status, String modVersion, String minecraftVersion, String loader) {
        NetworkStatus s = status == null ? NetworkStatus.idle() : status;
        List<String> lines = new ArrayList<>();
        boolean idle = "idle".equals(s.path()) && s.mode() == null;
        String state = idle ? "未连接" : s.receivedAt() == null ? "等待网络观测" : s.isStale() ? "数据已过期（历史观测）" : "会话运行中";
        lines.add("Tailcat 状态：" + state);
        lines.add("角色：" + ("host".equals(s.mode()) ? "房主" : "join".equals(s.mode()) ? "访客" : "无"));
        lines.add("实际路径：" + pathLabel(s.path()) + (s.isStale() ? "（已过期）" : ""));
        lines.add("当前中继：" + known(s.relay()));
        lines.add("引导中继（不代表实际转发）：" + known(s.bootstrapRelay()));
        lines.add("观测延迟：" + (s.latencyMs() == null ? "未知" : String.format(Locale.ROOT, "%.2f ms", s.latencyMs())));
        if (s.peers() == null) lines.add("对端：未知");
        else if (s.peers().isEmpty()) lines.add("对端：无");
        else for (NetworkStatus.PeerStatus peer : s.peers()) {
            lines.add("对端 " + peer.id() + "：" + pathLabel(peer.path())
                    + (peer.relay() == null ? "" : "；当前中继：" + peer.relay()));
        }
        if (s.omittedPeers() != null && s.omittedPeers() > 0)
            lines.add("另有 " + s.omittedPeers() + " 条活跃对端观测未展开（列表最多 64 条）");
        lines.add("活动连接：" + (s.activeConnections() == null ? "未知" : s.activeConnections())
                + "；运行时长：" + (s.uptimeSeconds() == null ? "未知" : String.format(Locale.ROOT, "%.0f 秒", s.uptimeSeconds())));
        String qualifier = s.isStale() ? "历史速率" : "当前速率";
        lines.add(qualifier + "：上传 " + rate(s.txBytesPerSecond()) + "；下载 " + rate(s.rxBytesPerSecond()));
        lines.add("会话采样峰值（不是链路最大容量）：上传 " + rate(s.peakTxBytesPerSecond()) + "；下载 " + rate(s.peakRxBytesPerSecond()));
        lines.add("版本：Mod " + known(modVersion) + "；Helper " + known(s.helperVersion()) + "；Tailcat " + known(s.tailcatVersion()));
        lines.add("Minecraft " + known(minecraftVersion) + "；加载器 " + known(loader));
        return Compatibility.immutableListCopy(lines);
    }
    private static String pathLabel(String path) {
        switch (path == null ? "unknown" : path) {
            case "udp": return "UDP 直连";
            case "derp": return "DERP 中继";
            case "mixed": return "UDP / DERP 混合";
            case "idle": return "等待连接";
            default: return "未知（等待实际观测）";
        }
    }
    public static String rate(Double bytesPerSecond) {
        if (bytesPerSecond == null || !Double.isFinite(bytesPerSecond) || bytesPerSecond < 0) return "未知";
        double value = bytesPerSecond;
        String unit = "B/s";
        if (value >= 1048576) { value /= 1048576; unit = "MiB/s"; }
        else if (value >= 1024) { value /= 1024; unit = "KiB/s"; }
        return String.format(Locale.ROOT, "%.2f %s (%.2f Mbps)", value, unit, bytesPerSecond / 125000);
    }
    static String known(String value) { return value == null || Compatibility.isBlank(value) ? "未知" : value; }
}
