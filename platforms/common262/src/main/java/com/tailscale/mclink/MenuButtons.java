package com.tailscale.mclink;
import java.util.function.Consumer;
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
            add.accept(Button.builder(Component.translatable("mclink.share"), b -> client.setScreenAndShow(new ShareScreen(screen)))
                    .bounds(8, 8, 134, 20).build());
        } else if (screen instanceof JoinMultiplayerScreen) {
            add.accept(Button.builder(Component.translatable("mclink.join"), b -> client.setScreenAndShow(new JoinRemoteScreen(screen)))
                    .bounds(8, 8, 134, 20).build());
        }
    }
}
