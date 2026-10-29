package com.tailscale.mclink;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
@Mod("tailcat")
public final class TailcatForge {
    public TailcatForge() { if (FMLEnvironment.dist == Dist.CLIENT) Client.register(); }
    private static final class Client {
        static void register() {
            TailcatClient.initialize("Forge " + net.minecraftforge.fml.ModList.get().getModContainerById("forge")
                    .map(mod -> mod.getModInfo().getVersion().toString()).orElse("unknown"), !FMLEnvironment.production);
            MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> MenuButtons.add(event.getScreen(), event::addListener));
            MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
                if (event.phase == TickEvent.Phase.END) TailcatClient.tick();
            });
            MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> TailcatClient.disconnected());
            MinecraftForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> TailcatClient.shutdown());
            MinecraftForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> {
                event.getDispatcher().register(Commands.literal("tc-status").executes(context -> TailcatClient.statusCommand())
                        .then(Commands.argument("arguments", StringArgumentType.greedyString()).executes(context -> TailcatClient.usageCommand("tc-status"))));
                event.getDispatcher().register(Commands.literal("tc-speedtest").executes(context -> TailcatClient.speedtestCommand())
                        .then(Commands.argument("arguments", StringArgumentType.greedyString()).executes(context -> TailcatClient.usageCommand("tc-speedtest"))));
            });
        }
    }
}
