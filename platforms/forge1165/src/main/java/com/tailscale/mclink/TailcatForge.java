package com.tailscale.mclink;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
@Mod("tailcat")
public final class TailcatForge {
    public TailcatForge() {
        net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                net.minecraftforge.fml.ExtensionPoint.DISPLAYTEST,
                () -> org.apache.commons.lang3.tuple.Pair.of(
                        () -> net.minecraftforge.fml.network.FMLNetworkConstants.IGNORESERVERONLY,
                        (remoteVersion, remoteIsServer) -> true));
        if (FMLEnvironment.dist == Dist.CLIENT) Client.register();
    }
    private static final class Client {
        static void register() {
            TailcatClient.initialize("Forge " + net.minecraftforge.fml.ModList.get().getModContainerById("forge")
                    .map(mod -> mod.getModInfo().getVersion().toString()).orElse("unknown"), !FMLEnvironment.production);
            MinecraftForge.EVENT_BUS.addListener((GuiScreenEvent.InitGuiEvent.Post event) -> MenuButtons.add(event.getGui(), event::addWidget));
            MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
                if (event.phase == TickEvent.Phase.END) TailcatClient.tick();
            });
            MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggedOutEvent event) -> TailcatClient.disconnected());
            Runtime.getRuntime().addShutdownHook(new Thread(TailcatClient::shutdown, "tailcat-shutdown"));
            MinecraftForge.EVENT_BUS.addListener((ClientChatEvent event) -> {
                String message = event.getMessage();
                String command = message.split("\\s+", 2)[0];
                if (command.equals("/tc-status") || command.equals("/tc-speedtest")) {
                    event.setCanceled(true);
                    if (!message.equals(command)) TailcatClient.usageCommand(command.substring(1));
                    else if (command.equals("/tc-status")) TailcatClient.statusCommand();
                    else TailcatClient.speedtestCommand();
                }
            });
        }
    }
}
