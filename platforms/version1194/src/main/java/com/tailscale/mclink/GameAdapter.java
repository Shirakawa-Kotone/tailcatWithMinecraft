package com.tailscale.mclink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
final class GameAdapter {
    static void connect(Screen parent, String address) {
        Minecraft client = Minecraft.getInstance();
        ConnectScreen.startConnecting(parent, client, ServerAddress.parseString(address),
                new ServerData("Tailcat World", address, false));
    }
}
