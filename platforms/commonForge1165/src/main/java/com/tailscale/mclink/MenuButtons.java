package com.tailscale.mclink;
import java.util.function.Consumer;
import net.minecraft.client.gui.screen.AlertScreen;
import net.minecraft.client.gui.screen.ShareToLanScreen;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.IngameMenuScreen;
import net.minecraft.client.gui.screen.MultiplayerScreen;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
public final class MenuButtons {
    private MenuButtons() {}
    public static void add(Screen screen, Consumer<Button> add) {
        Minecraft client = Minecraft.getInstance();
        if (screen instanceof IngameMenuScreen && client.getSingleplayerServer() != null) {
            add.accept(LegacyUi.button(new TranslationTextComponent("mclink.share"), b -> client.setScreen(new ShareScreen(screen)))
                    .bounds(8, 8, 134, 20).build());
            // Vanilla disables or replaces its LAN button after the world is published.
            // Keep a separate status entry without rebuilding the active listener.
            if (client.getSingleplayerServer().isPublished()) {
                add.accept(LegacyUi.button(new TranslationTextComponent("mclink.lan"), b -> openLanStatus(client, screen))
                        .bounds(screen.width - 142, 8, 134, 20).build());
            }
        } else if (screen instanceof MultiplayerScreen) {
            add.accept(LegacyUi.button(new TranslationTextComponent("mclink.join"), b -> client.setScreen(new JoinRemoteScreen(screen)))
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
                new TranslationTextComponent("mclink.lan"),
                new TranslationTextComponent("mclink.lan_status", server.getPort()),
                new TranslationTextComponent("gui.back")) {
            @Override public void onClose() { client.setScreen(parent); }
        });
    }
}
