package com.tailscale.mclink;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
@Mod("tailcat")
public final class TailcatNeoForge {
    public TailcatNeoForge() { if (FMLEnvironment.getDist() == Dist.CLIENT) Client.register(); }
    private static final class Client {
        static void register() {
            TailcatClient.initialize("NeoForge " + net.neoforged.fml.ModList.get().getModContainerById("neoforge")
                    .map(mod -> mod.getModInfo().getVersion().toString()).orElse("unknown"), !FMLEnvironment.isProduction());
            NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> MenuButtons.add(event.getScreen(), event::addListener));
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> TailcatClient.tick());
            NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> TailcatClient.disconnected());
            NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> TailcatClient.shutdown());
            NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> {
                event.getDispatcher().register(Commands.literal("tc-status").executes(context -> TailcatClient.statusCommand())
                        .then(Commands.argument("arguments", StringArgumentType.greedyString()).executes(context -> TailcatClient.usageCommand("tc-status"))));
                event.getDispatcher().register(Commands.literal("tc-speedtest").executes(context -> TailcatClient.speedtestCommand())
                        .then(Commands.argument("arguments", StringArgumentType.greedyString()).executes(context -> TailcatClient.usageCommand("tc-speedtest"))));
            });
        }
    }
}
