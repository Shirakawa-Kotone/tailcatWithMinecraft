package com.tailscale.mclink.bootstrap;

@net.minecraftforge.fml.common.Mod("tailcat")
public final class ForgeBootstrap {
    public ForgeBootstrap() { BootstrapRuntime.launch(RuntimeEnvironment.forgeLoader(ForgeBootstrap.class.getClassLoader())); }
}
