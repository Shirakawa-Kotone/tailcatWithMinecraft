package com.tailscale.mclink;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
public final class MenuButtons {
    private MenuButtons() {}
    public static void add(Screen screen, Consumer<Button> add) {
        Minecraft client = Minecraft.getInstance();
        if (screen instanceof PauseScreen && client.getSingleplayerServer() != null) {
            add.accept(LegacyUi.button(Component.translatable("mclink.share"), b -> client.setScreen(new ShareScreen(screen)))
                    .bounds(8, 8, 134, 20).build());
            // Vanilla disables or replaces its LAN button after the world is published.
            // Keep a separate status entry without rebuilding the active listener.
            if (client.getSingleplayerServer().isPublished()) {
                add.accept(LegacyUi.button(Component.translatable("mclink.lan"), b -> openLanStatus(client, screen))
                        .bounds(screen.width - 142, 8, 134, 20).build());
            }
        } else if (screen instanceof JoinMultiplayerScreen) {
            add.accept(LegacyUi.button(Component.translatable("mclink.join"), b -> client.setScreen(new JoinRemoteScreen(screen)))
                    .bounds(8, 8, 134, 20).build());
        }
    }
    private static void openLanStatus(Minecraft client, Screen parent) {
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null) return;
        if (!server.isPublished()) {
            client.setScreen(new ShareToLanScreen(parent));
            return;
        }
        client.setScreen(new AlertScreen(() -> client.setScreen(parent),
                Component.translatable("mclink.lan"),
                Component.translatable("mclink.lan_status", server.getPort()),
                Component.translatable("gui.back"), true) {
            @Override public void onClose() { client.setScreen(parent); }
        });
    }
}
