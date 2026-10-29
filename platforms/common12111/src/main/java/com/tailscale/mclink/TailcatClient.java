package com.tailscale.mclink;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.io.InputStream;
import java.util.Properties;

/** Loader-neutral entry point; all game state mutations run on the Minecraft thread. */
public final class TailcatClient {
    public static final String MOD_ID = "tailcat";
    private static ScreenState state;
    private static String modVersion = "unknown", loader = "unknown";
    private TailcatClient() {}
    public static void initialize(String loaderName, boolean development) {
        if (state != null) return;
        loader = loaderName;
        Properties build = new Properties();
        try (InputStream input = TailcatClient.class.getResourceAsStream("/tailcat-build.properties")) {
            if (input != null) { build.load(input); modVersion = build.getProperty("version", "unknown"); }
        } catch (Exception ignored) { /* Diagnostics explicitly report unknown on a broken resource. */ }
        state = new ScreenState(modVersion, development);
    }
    public static ScreenState state() { return state; }
    public static void tick() { if (state != null) state.tick(); }
    public static void disconnected() { if (state != null) state.disconnected(); }
    public static void shutdown() { if (state != null) state.close(); }
    public static int statusCommand() {
        for (String line : StatusFormatter.lines(state.status(), modVersion,
                SharedConstants.getCurrentVersion().name(), loader)) message(line);
        return 1;
    }
    public static int speedtestCommand() { state.speedtest(); return 1; }
    public static int usageCommand(String command) {
        message("Tailcat：用法 /" + command + "（无参数）");
        return 1;
    }
    static void message(String message) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.gui.getChat().addMessage(Component.literal(message)));
    }
}
