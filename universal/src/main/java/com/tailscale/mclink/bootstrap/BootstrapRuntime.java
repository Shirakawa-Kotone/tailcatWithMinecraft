package com.tailscale.mclink.bootstrap;

import java.io.IOException;
import java.nio.file.Path;

/** Holds the selected adapter classloader for the entire game session. */
public final class BootstrapRuntime {
    private static boolean started;
    private static AdapterLoader activeAdapter;
    private BootstrapRuntime() {}
    public static synchronized void launch(String loader) {
        if (started) return;
        started = true;
        try {
            ClassLoader parent = BootstrapRuntime.class.getClassLoader();
            AdapterIndex index = AdapterIndex.read(parent.getResourceAsStream("META-INF/tailcat/adapters.json"));
            String minecraft = RuntimeEnvironment.minecraft(loader, parent);
            AdapterIndex.Entry entry = index.select(loader, minecraft,
                    RuntimeEnvironment.javaMajor(System.getProperty("java.version")));
            Path adapter = AdapterCache.extract(RuntimeEnvironment.gameDirectory(loader, parent), index.modVersion,
                    entry, parent::getResourceAsStream);
            activeAdapter = AdapterLoader.launch(adapter, entry, parent);
        } catch (IOException error) {
            throw new IllegalStateException("Tailcat universal startup failed: " + error.getMessage(), error);
        }
    }
}
