package com.tailscale.mclink;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreProtocolTest {
    @Test void boundedPeerSnapshotReportsOmittedObservations() throws Exception {
        com.google.gson.JsonObject object = new JsonParser().parse("{\"mode\":\"host\",\"path\":\"udp\",\"omittedPeers\":236,\"peers\":[]}").getAsJsonObject();
        com.google.gson.JsonArray peers = object.getAsJsonArray("peers");
        for (int i = 0; i < 64; i++) peers.add(new JsonParser().parse("{\"id\":\"peer-" + i + "\",\"path\":\"udp\"}"));
        NetworkStatus snapshot = NetworkStatus.parse(object);
        assertEquals(64, snapshot.peers().size());
        assertTrue(String.join("\n", StatusFormatter.lines(snapshot, "0.2.0", "1.21.1", "Fabric")).contains("236"));
    }
    @Test void rejectsMalformedLoopbackAndProtocolVersions() throws Exception {
        for (String address : new String[]{"127.0.0.1:0", "127.0.0.1:65536", "127.0.0.1:42:7", "127.0.0.1:abc", "127.0.0.1:1/path", "localhost:25565", "127.0.0.1.evil:42"})
            assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"ready\",\"protocolVersion\":1,\"mode\":\"join\",\"address\":\"" + address + "\"}"));
        assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"ready\",\"protocolVersion\":2,\"mode\":\"host\",\"invite\":\"secret\"}"));
        assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"ready\",\"mode\":\"host\",\"invite\":\"secret\"}"));
        assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":true}"));
        assertThrows(IOException.class, () -> HelperEvent.parse(new String(new char[65_537]).replace('\0', ' ')));
        assertEquals("127.0.0.1:25565", HelperEvent.parse("{\"type\":\"ready\",\"protocolVersion\":1,\"mode\":\"join\",\"address\":\"127.0.0.1:25565\"}").address());
    }
    @Test void statusRejectsInvalidNumericValues() {
        for (String value : new String[]{"-1", "\"12\"", "true", "{}", "1e9999"}) {
            assertThrows(IOException.class, () -> NetworkStatus.parse(new JsonParser().parse("{\"txBytesPerSecond\":" + value + "}").getAsJsonObject()));
        }
        assertThrows(IOException.class, () -> NetworkStatus.parse(new JsonParser().parse("{\"activeConnections\":1.5}").getAsJsonObject()));
        assertThrows(IOException.class, () -> NetworkStatus.parse(new JsonParser().parse("{\"txBytes\":9223372036854775808}").getAsJsonObject()));
    }
    @Test void unknownIdleAndStaleAreTruthful() throws Exception {
        NetworkStatus unknown = NetworkStatus.parse(new JsonParser().parse("{\"mode\":\"join\",\"path\":\"unknown\",\"bootstrapRelay\":\"bootstrap.example\"}").getAsJsonObject());
        String lines = String.join("\n", StatusFormatter.lines(unknown, "0.1", "1.20.1", "Fabric"));
        assertTrue(lines.contains("未知"));
        assertTrue(lines.contains("引导"));
        assertFalse(lines.contains("当前中继：bootstrap.example"));
        assertFalse(lines.contains("0 B/s"));
        assertTrue(String.join("\n", StatusFormatter.lines(NetworkStatus.idle(), "0.1", "1.20.1", "Fabric")).contains("未连接"));
        assertTrue(unknown.isStale(Instant.now().plusSeconds(10)));
        assertFalse(unknown.isStale(Instant.now()));
    }
    @Test void udpStatusRetainsBootstrapRelayAndLatencyWithoutInventingAnActiveRelay() throws Exception {
        NetworkStatus status = NetworkStatus.parse(new JsonParser().parse("{\"mode\":\"join\",\"path\":\"udp\",\"bootstrapRelay\":\"bootstrap.example\",\"latencyMs\":4.5}").getAsJsonObject());
        String text = String.join("\n", StatusFormatter.lines(status, "0.1", "1.20.1", "Fabric"));
        assertTrue(text.contains("引导中继（不代表实际转发）：bootstrap.example"));
        assertFalse(text.contains("当前中继：bootstrap.example"));
        assertTrue(text.contains("4.50 ms"));
    }
    @Test void derpStatusSeparatesActiveRelayFromItsDifferentBootstrapRelay() throws Exception {
        NetworkStatus status = NetworkStatus.parse(new JsonParser().parse("{\"mode\":\"host\",\"path\":\"derp\",\"relay\":\"active.example\",\"bootstrapRelay\":\"bootstrap.example\"}").getAsJsonObject());
        String text = String.join("\n", StatusFormatter.lines(status, "0.1", "1.20.1", "Forge"));
        assertTrue(text.contains("当前中继：active.example"));
        assertTrue(text.contains("引导中继（不代表实际转发）：bootstrap.example"));
        assertFalse(text.contains("引导中继（不代表实际转发）：active.example"));
    }
    @Test void activeRelayIsNeverReclassifiedAsBootstrapWhenPathIsUnknown() throws Exception {
        NetworkStatus status = NetworkStatus.parse(new JsonParser().parse("{\"mode\":\"host\",\"path\":\"unknown\",\"relay\":\"active.example\"}").getAsJsonObject());
        String text = String.join("\n", StatusFormatter.lines(status, "0.1", "1.20.1", "Forge"));
        assertTrue(text.contains("当前中继：active.example"));
        assertFalse(text.contains("引导中继（不代表实际转发）：active.example"));
    }
    @Test void mixedPathDisplaysIndividualObservedPeers() throws Exception {
        NetworkStatus status = NetworkStatus.parse(new JsonParser().parse("{\"mode\":\"host\",\"path\":\"mixed\",\"peers\":[{\"id\":\"visitor-a\",\"path\":\"udp\"},{\"id\":\"visitor-b\",\"path\":\"derp\",\"relay\":\"active.example\"}]}").getAsJsonObject());
        String text = String.join("\n", StatusFormatter.lines(status, "0.1", "1.20.1", "NeoForge"));
        assertTrue(text.contains("visitor-a：UDP 直连"));
        assertTrue(text.contains("visitor-b：DERP 中继"));
        assertTrue(text.contains("active.example"));
    }
    @Test void rejectsMalformedLatencyAndPeerTypes() {
        assertThrows(IOException.class, () -> NetworkStatus.parse(new JsonParser().parse("{\"latencyMs\":-1}").getAsJsonObject()));
        assertThrows(IOException.class, () -> NetworkStatus.parse(new JsonParser().parse("{\"peers\":{}}").getAsJsonObject()));
        assertThrows(IOException.class, () -> NetworkStatus.parse(new JsonParser().parse("{\"peers\":[{\"id\":7,\"path\":\"udp\"}]}").getAsJsonObject()));
    }
    @Test void reportsMeasuredRatesAndSampledPeaks() throws Exception {
        NetworkStatus status = NetworkStatus.parse(new JsonParser().parse("{\"mode\":\"host\",\"path\":\"udp\",\"txBytesPerSecond\":1048576,\"rxBytesPerSecond\":1024,\"peakTxBytesPerSecond\":2097152,\"activeConnections\":2,\"uptimeSeconds\":65}").getAsJsonObject());
        String text = String.join("\n", StatusFormatter.lines(status, "0.1", "1.20.1", "Forge"));
        assertTrue(text.contains("UDP"));
        assertTrue(text.contains("1.00 MiB/s"));
        assertTrue(text.contains("8.39 Mbps"));
        assertTrue(text.contains("采样峰值"));
        assertTrue(text.contains("不是链路最大容量"));
        assertTrue(text.contains("Forge"));
    }
    @Test void speedtestValidatesStateAndFormatsResults() throws Exception {
        HelperEvent event = HelperEvent.parse("{\"type\":\"speedtest\",\"protocolVersion\":1,\"speedtest\":{\"state\":\"complete\",\"uploadBytesPerSecond\":1000000,\"downloadBytesPerSecond\":2000000,\"latencyMs\":3.5}}");
        assertEquals("complete", event.speedtest().state());
        assertTrue(String.join("\n", event.speedtest().lines()).contains("8.00 Mbps"));
        assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"speedtest\",\"protocolVersion\":1,\"speedtest\":{\"state\":\"complete\",\"latencyMs\":-2}}"));
    }
    @Test void snapshotOwnsImmutablePeerObservationsAndKeepsValueSemantics() {
        java.util.List<NetworkStatus.PeerStatus> peers = new java.util.ArrayList<>();
        peers.add(new NetworkStatus.PeerStatus("visitor", "udp", null));
        NetworkStatus snapshot = new NetworkStatus("host", "udp", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, peers, null);
        peers.clear();
        assertEquals(1, snapshot.peers().size());
        assertEquals(new NetworkStatus.PeerStatus("visitor", "udp", null), snapshot.peers().get(0));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.peers().clear());
        assertEquals(new Platform("linux", "amd64"), Platform.parse("Linux", "x86_64"));
        assertEquals(new Platform("linux", "amd64").hashCode(), Platform.parse("Linux", "x86_64").hashCode());
    }
    @Test void unicodeBlankInvitationIsRejectedAndDiagnosticsRemainRedacted() throws Exception {
        assertThrows(IOException.class, () -> HelperEvent.parse("{\"type\":\"ready\",\"protocolVersion\":1,\"mode\":\"host\",\"invite\":\"\u2003\"}"));
        HelperEvent event = HelperEvent.parse("{\"type\":\"ready\",\"protocolVersion\":1,\"mode\":\"host\",\"invite\":\"secret-value\"}");
        assertFalse(event.toString().contains("secret-value"));
        assertEquals("未知", StatusFormatter.known("\u2003"));
    }
    @Test void platformMappingIsPortableAndRejectsUnknown() {
        assertEquals("darwin-arm64", Platform.parse("Mac OS X", "aarch64").resourceDirectory());
        assertEquals("windows-amd64", Platform.parse("Windows 11", "x86_64").resourceDirectory());
        assertEquals("mclink-helper.exe", Platform.parse("Windows 11", "amd64").executableName());
        assertEquals("linux-arm64", Platform.parse("Linux", "arm64").resourceDirectory());
        assertThrows(IllegalStateException.class, () -> Platform.parse("FreeBSD", "amd64"));
    }
}
