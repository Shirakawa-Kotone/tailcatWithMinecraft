package com.tailscale.mclink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
final class GameAdapter {
 static void connect(Screen parent, String address) {
  Minecraft client = Minecraft.getInstance();
  client.setScreen(new ConnectScreen(parent, client, new ServerData("Tailcat World",address,false)));
 }
}
