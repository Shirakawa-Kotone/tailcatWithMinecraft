package com.tailscale.mclink;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import com.mojang.brigadier.arguments.StringArgumentType;
public final class TailcatFabric implements ClientModInitializer {
    @Override public void onInitializeClient() {
        TailcatClient.initialize("Fabric " + FabricLoader.getInstance().getModContainer("fabricloader")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown"), FabricLoader.getInstance().isDevelopmentEnvironment());
        // 26.x exposes screen widgets as AbstractWidget; Buttons remain valid members.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> MenuButtons.add(screen, button -> Screens.getWidgets(screen).add(button)));
        ClientTickEvents.END_CLIENT_TICK.register(client -> TailcatClient.tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TailcatClient.disconnected());
        ClientLoginConnectionEvents.DISCONNECT.register((handler, client) -> TailcatClient.disconnected());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> TailcatClient.shutdown());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(literal("tc-status").executes(context -> TailcatClient.statusCommand())
                    .then(argument("arguments", StringArgumentType.greedyString()).executes(context -> TailcatClient.usageCommand("tc-status"))));
            dispatcher.register(literal("tc-speedtest").executes(context -> TailcatClient.speedtestCommand())
                    .then(argument("arguments", StringArgumentType.greedyString()).executes(context -> TailcatClient.usageCommand("tc-speedtest"))));
        });
    }
}
