package com.tailscale.mclink;

import java.io.DataInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Legacy Minecraft starts the entire shared core on Java 8. */
class Java8CompatibilityTest {
    @Test void sharedRuntimeClassesHaveJava8Bytecode() throws Exception {
        for (Class<?> type : new Class<?>[]{Platform.class, HelperEvent.class, NetworkStatus.class,
                NetworkStatus.PeerStatus.class, SpeedTestResult.class, JsonFields.class,
                StatusFormatter.class, JoinLifecycle.class, HelperProcess.class, NativeHelper.class, Compatibility.class, HostAuthentication.class}) {
            try (InputStream stream = type.getResourceAsStream(type.getSimpleName().equals("PeerStatus")
                    ? "NetworkStatus$PeerStatus.class" : type.getSimpleName() + ".class");
                 DataInputStream data = new DataInputStream(stream)) {
                assertEquals(0xcafebabe, data.readInt());
                data.readUnsignedShort();
                assertEquals(52, data.readUnsignedShort(), type.getName() + " cannot load on Java 8");
            }
        }
    }
}
