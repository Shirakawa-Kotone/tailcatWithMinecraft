package com.tailscale.mclink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.multiplayer.ServerData;
final class GameAdapter {
 static void connect(Screen parent, String address) {
  Minecraft client = Minecraft.getInstance();
  client.setScreen(new ConnectingScreen(parent, client, new ServerData("Tailcat World",address,false)));
 }
}
