package com.tailscale.mclink.bootstrap;

/** Fabric only discovers this Java 8 outer entrypoint. */
public final class FabricBootstrap implements net.fabricmc.api.ClientModInitializer {
    @Override public void onInitializeClient() { BootstrapRuntime.launch("fabric"); }
}
