package com.tailscale.mclink;
import java.util.function.Consumer;
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
        } else if (screen instanceof MultiplayerScreen) {
            add.accept(LegacyUi.button(new TranslationTextComponent("mclink.join"), b -> client.setScreen(new JoinRemoteScreen(screen)))
                    .bounds(8, 8, 134, 20).build());
        }
    }
}
